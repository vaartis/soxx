package controllers

import akka.util.Timeout
import org.mongodb.scala._
import scala.concurrent.duration._
import scala.util._
import scala.concurrent._

import javax.inject._
import play.api._
import play.api.mvc._
import play.api.libs.json._

import akka.actor._

import soxx.mongowrapper.Mongo
import soxx.scrappers._

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class SoxxController @Inject()(
  cc: ControllerComponents,
  system: ActorSystem,
  mongo: Mongo
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  implicit val actorResolveTimeout: Timeout = 5 seconds

  val scrapperSupervisor = Await.result(
    system
      .actorSelection(system / "scrapper-supervisor")
      .resolveOne(),
    Duration.Inf
  )


  def index() = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.index())
  }

  def stop() = Action { implicit request: Request[AnyContent] =>
    system
      .actorSelection(scrapperSupervisor.path / "safebooru-scrapper")
      .resolveOne()
      .recover { case e => println(e); throw e }
      .andThen {
        case Success(actRef) =>
          actRef ! StopIndexing
      }

    Ok(views.html.index())
  }

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

    Ok(
      Json.toJson(
        Await.result(
          mongo.db
            .getCollection[Image]("images")
            .find(/* Add query support */)
            .skip(offset)
            .limit(limit)
            .sort(Document("_id" -> -1))
            .toFuture,
          Duration.Inf
        )
      )
    )
  }

}
