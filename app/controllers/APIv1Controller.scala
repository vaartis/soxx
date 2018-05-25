package controllers.apiv1 // Hack to make it recompile correctly

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
class APIv1Controller @Inject()(
  cc: ControllerComponents,
  system: ActorSystem,
  mongo: Mongo
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

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
            Await.result(collection.find(Document("_id" -> name)).toFuture, 5 seconds)
          case None =>
            Await.result(collection.find().toFuture, 5 seconds)
        }
      )
    )
  }

  def images(query: Option[String], offset: Int, _limit: Int) = Action { implicit request: Request[AnyContent] =>
    // Hard-limit "limit" to 250
    val limit: Int = if (_limit > 250) { 250 } else { _limit }

    val imageCollection = mongo.db.getCollection[Image]("images")

    if (query.nonEmpty) {
      import searchQueryParser.{Success, Failure, Error}
      import org.mongodb.scala.model.Filters._

      searchQueryParser.parseQuery(query.get) match {
        case Success(tagList, _) =>
          val tagFilters = tagList.map {
            case FullTag(tag) =>
              Document("tags" -> Document("$in" -> Seq(tag)))
            case ExcludeTag(tag) =>
              Document("tags" -> Document("$not" -> Document("$in" -> Seq(tag))))
            case RegexTag(tag) =>
              Document("tags" -> Document("$regex" -> tag.regex, "$options" -> "i"))
          }

          Ok(
            Json.toJson(
              Json.obj(
                "ok" -> true,
                "result" -> Json.toJson(Await.result(
                  imageCollection
                    .find(and(tagFilters:_*))
                    .skip(offset)
                    .limit(limit)
                    .sort(Document("_id" -> -1))
                    .toFuture,
                  Duration.Inf
                )
              )),
            )
          )
      }
    } else {
          Ok(
            Json.toJson(
              Json.obj(
                "ok" -> true,
                "result" -> Json.toJson(Await.result(
                  imageCollection
                    .find()
                    .skip(offset)
                    .limit(limit)
                    .sort(Document("_id" -> -1))
                    .toFuture,
                  Duration.Inf
                )
              )),
            )
          )
    }
  }

  def test_index() = Action { implicit request: Request[AnyContent] =>
    system
      .actorSelection(scrapperSupervisor.path / "safebooru-scrapper")
      .resolveOne()
      .recover { case e => println(e); throw e }
      .andThen {
        case Success(actRef) =>
          actRef ! StartIndexing(fromPage = 1)
      }

    Ok("OK");

    // Ok(views.html.index())
  }
}
