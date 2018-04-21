package controllers

import scala.concurrent.duration._
import scala.util._
import scala.concurrent._

import javax.inject._
import play.api._
import play.api.mvc._

import akka.actor._
import akka.util.Timeout

import soxx.mongowrapper.Mongo
import soxx.scrappers._

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class HomeController @Inject()(
  cc: ControllerComponents,
  system: ActorSystem
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  implicit val resolveTimeout = Timeout(FiniteDuration(5, SECONDS))

  val scrapperSupervisor = Await.result(
    system
      .actorSelection(system / "scrapper-supervisor")
      .resolveOne(),
    Duration.Inf
  )

  /**
   * Create an Action to render an HTML page.
   *
   * The configuration in the `routes` file means that this method
   * will be called when the application receives a `GET` request with
   * a path of `/`.
   */
  def index() = Action { implicit request: Request[AnyContent] =>
    system
      .actorSelection(scrapperSupervisor.path / "safebooru-scrapper")
      .resolveOne()
      .recover { case e => println(e); throw e }
      .andThen {
        case Success(actRef) =>
          actRef ! StartIndexing(fromPage = 1)
      }

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

}
