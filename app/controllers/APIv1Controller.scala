package controllers.apiv1 // Hack to make it recompile correctly

import scala.language.postfixOps

import javax.inject._
import scala.concurrent._
import scala.util._
import scala.concurrent.duration._

import akka.actor._
import akka.util._
import akka.stream.Materializer
import play.api.mvc._
import play.api.libs.json._
import org.mongodb.scala._

import soxx.mongowrapper._
import soxx.search._
import soxx.scrappers._
import soxx.helpers.Helpers.RequestHelpers

@Singleton
class APIv1Controller @Inject()
  (
    cc: ControllerComponents
  )
  (
    implicit ec: ExecutionContext,
    mat: Materializer,
    system: ActorSystem,
    mongo: Mongo
  ) extends AbstractController(cc) {

  implicit val actorResolveTimeout: Timeout = 5 seconds

  lazy val scrapperSupervisor = Await.result(
    system
      .actorSelection(system / "scrapper-supervisor")
      .resolveOne(),
    Duration.Inf
  )

  def imboard_info(name: Option[String]) = Action.async { implicit request: Request[AnyContent] =>
    val collection = mongo.db.getCollection[BoardInfo]("imboard_info")

    name
      .map { nm => collection.find(Document("_id" -> nm)).toFuture }
      .getOrElse { collection.find().toFuture }
      .map { v => Ok(Json.toJson(v)) }
  }

  def image(id: String) = Action.async { implicit request: Request[AnyContent] =>
    import org.mongodb.scala.model.Filters.equal
    import org.bson.types.ObjectId

    mongo.db.getCollection[Image]("images")
      .find(equal("_id", new ObjectId(id)))
      .toFuture
      .map {
        case Seq(theImage) =>
          Ok(
            Json.obj(
              "ok" -> true,
              "result" -> Json.toJson(theImage.toFrontend(request.hostWithProtocol))
            )
          )
        case Seq() => Ok(Json.obj("ok" -> false, "error" -> f"The image ${id} doesn't exist"))
      }
  }

  def images(query: Option[String], offset: Int, _limit: Int) = Action.async { implicit request: Request[AnyContent] =>
    // Hard-limit "limit" to 250
    val limit: Int = if (_limit > 250) { 250 } else { _limit }

    val imageCollection = mongo.db.getCollection[Image]("images")

    APIv1Controller.tagStringToQuery(query) match {
      case Right(mongoSearchQuery) =>
        imageCollection
          .countDocuments(mongoSearchQuery)
          .toFuture()
          .flatMap { foundImageCount =>
            imageCollection.find(mongoSearchQuery)
              .skip(offset)
              .limit(limit)
              .sort(Document("_id" -> -1))
              .toFuture
              .map { images =>
                // Make it an actual result and return the future

                Ok(
                  Json.obj(
                    "ok" -> true,
                    "result" ->
                      Json.obj(
                        "images" -> images.map(i => Json.toJson(i.toFrontend(request.hostWithProtocol))),
                        "imageCount" -> foundImageCount
                      )
                  )
                )
              }
          }
      case Left(errorString) =>
        Future { Ok(Json.obj("ok" -> false, "error" -> errorString)) }
    }
  }

  def similar_images(id: String, offset: Int, _resultLimit: Int) = Action.async { implicit request: Request[AnyContent] =>
    import org.bson.types.ObjectId
    import org.mongodb.scala._
    import org.mongodb.scala.bson.{ Document => _, _ }
    import org.mongodb.scala.model.Filters._
    import org.mongodb.scala.model.Aggregates._
    import org.mongodb.scala.model.Accumulators._
    import org.mongodb.scala.model.Sorts._
    import org.mongodb.scala.model.Projections._

    val resultLimit: Int = if (_resultLimit > 250) { 250 } else { _resultLimit }

    val imageCollection = mongo.db.getCollection[Image]("images")

    try {
      imageCollection.find(equal("_id", new ObjectId(id)))
        .headOption
        .flatMap { imageOpt =>

          imageOpt match {
            case Some(image) =>
              imageCollection
                .aggregate(Seq(
                  `match`(not(equal("_id", image._id))),
                  unwind("$tags"),
                  `match`(in("tags", image.tags:_*)),
                  group("$_id", sum("count", 1)),
                  project(fields(
                    computed(
                      "score",
                      equal("$divide", Seq("$count", image.tags.length))
                    )
                  )),
                  sort(descending("score")),
                  // Because the document is not what it was and only includes an ID and a similarity value, we now retreive the whole document again
                  lookup(from = "images", localField = "_id", foreignField = "_id", as = "doc"),
                  replaceRoot(
                    equal("$arrayElemAt", Seq("$doc", 0))
                  ),
                  skip(offset),
                  limit(resultLimit)
                )).toFuture.map { imgs =>
                  Ok(Json.obj(
                    "ok" -> true,
                    "images" -> Json.toJson(imgs.map(_.toFrontend(request.hostWithProtocol)))
                  ))
                }
            case None => Future.successful(
              Ok(Json.obj(
                "ok" -> false,
                "error" -> f"The image $id doesn't exist"
              ))
            )
          }
        }
    } catch {
      case e: IllegalArgumentException =>
        Future.successful {
          Ok(Json.obj(
            "ok" -> false,
            "error" -> f"The image id $id is invalid ($e)"
          ))
        }
    }
  }

  def admin_panel_socket = WebSocket.accept[JsValue, JsValue] { req =>
    import play.api.libs.streams._
    import soxx.admin._

    ActorFlow.actorRef { out =>
      AdminPanelActor.props(out)
    }
  }
}

object APIv1Controller {

  /** Parses a tag string if there is one and returns either a parsing error or a MongoDB filter for these tags.
    *
    * @param query the query string to parse. The result will be an empty filter if there is no query
    */
  def tagStringToQuery(query: Option[String]): Either[String, org.bson.conversions.Bson] = {
    query
      .map { query =>
        // The left value is the error that might've happened while parsing
        // The right value is the parsed query

        import org.mongodb.scala.model.Filters._

        import QueryParser.{Success, NoSuccess}


        QueryParser.parseQuery(query) match {
          case Success(tagList, _) =>
            def transformTag(t: QueryTag): org.bson.conversions.Bson = t match {
              case SimpleTag(tag) => equal("tags", tag)
              case ExactTag(tag) => equal("tags", tag)
              case RegexTag(tag) => regex("tags", tag.regex, "i")

              case TagOR(left, right) => or(transformTag(left), transformTag(right))
              case TagAND(left, right) => and(transformTag(left), transformTag(right))
              case TagNOT(tag) => not(transformTag(tag))

              case TagGroup(group) => and(group.map(transformTag):_*)
            }

            // Put everything into an implicit AND
            Right(
              and(tagList.map(transformTag):_*)
            )
          case NoSuccess(errorString, _) =>
            Left(errorString)
        }
      }
      .getOrElse(Right(Document())) // Use an empty list if there is no query
  }
}
