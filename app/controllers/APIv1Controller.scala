package controllers.apiv1 // Hack to make it recompile correctly

import akka.stream.Materializer
import scala.language.postfixOps

import javax.inject._
import scala.concurrent._
import scala.util._
import scala.concurrent.duration._

import akka.actor._
import akka.util._
import play.api.mvc._
import play.api.libs.json._
import org.mongodb.scala._

import soxx.mongowrapper._
import soxx.search._
import soxx.scrappers._

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

  val searchQueryParser = new QueryParser

  implicit val actorResolveTimeout: Timeout = 5 seconds

  val scrapperSupervisor = Await.result(
    system
      .actorSelection(system / "scrapper-supervisor")
      .resolveOne(),
    Duration.Inf
  )

  def imboard_info(name: Option[String]) = Action { implicit request: Request[AnyContent] =>
    val collection = mongo.db
      .getCollection[BoardInfo]("imboard_info")

    Ok(
      Json.toJson(
        name match {
          case Some(nm) =>
            Await.result(collection.find(Document("_id" -> nm)).toFuture, 5 seconds)
          case None =>
            Await.result(collection.find().toFuture, 5 seconds)
        }
      )
    )
  }

  def image(id: String) = Action.async { implicit request: Request[AnyContent] =>
    import org.mongodb.scala.model.Filters.equal
    import org.bson.types.ObjectId

    mongo.db.getCollection[Image]("images")
      .find(equal("_id", new ObjectId(id)))
      .toFuture
      .map {
        case Seq(theImage) => Ok(Json.toJson(Json.obj("ok" -> true, "result" -> theImage)))
        case Seq() => Ok(Json.toJson(Json.obj("ok" -> false, "error" -> f"The image ${id} doesn't exist")))
      }
  }

  def images(query: Option[String], offset: Int, _limit: Int) = Action.async { implicit request: Request[AnyContent] =>
    // Hard-limit "limit" to 250
    val limit: Int = if (_limit > 250) { 250 } else { _limit }

    val imageCollection = mongo.db.getCollection[Image]("images")

    query
      .map { query =>
        // The left value is the error that might've happened while parsing
        // The right value is the parsed query

        import searchQueryParser.{Success, NoSuccess}

        searchQueryParser.parseQuery(query) match {
          case Success(tagList, _) =>
            Right(
              tagList.map {
                case FullTag(tag) =>
                  Document("tags" -> Document("$in" -> Seq(tag)))
                case ExcludeTag(tag) =>
                  Document("tags" -> Document("$not" -> Document("$in" -> Seq(tag))))
                case RegexTag(tag) =>
                  Document("tags" -> Document("$regex" -> tag.regex, "$options" -> "i"))
              }
            )
          case NoSuccess(errorString, _) =>
            Left(errorString)
        }
      }
      .getOrElse(Right(List())) // Use an empty list if there is no query
      .map { searchQuery =>
        import org.mongodb.scala.model.Filters._

        // Actually get the images
        // Just use an empty document if there's no filters
        val mongoSearchQuery = if (searchQuery.isEmpty) { Document() } else { and(searchQuery:_*) }

        imageCollection
          .count(mongoSearchQuery)
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
                        "images" -> images,
                        "imageCount" -> foundImageCount
                      )
                  )
                )
              }
          }
      } match {
        case Right(result) => result
        case Left(errorString) =>
          Future { Ok(Json.toJson(Json.obj("ok" -> false, "error" -> errorString))) }
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
