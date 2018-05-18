package controllers

import scala.language.postfixOps
import scala.concurrent.duration._
import scala.util._
import scala.concurrent._
import javax.inject._

import akka.util.Timeout
import play.api.mvc._
import akka.actor._

import soxx.scrappers._

@Singleton
class SoxxController @Inject()(
  cc: ControllerComponents,
  system: ActorSystem
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
}
