package controllers

import scala.concurrent._
import javax.inject._

import play.api.mvc._
import akka.actor._

@Singleton
class SoxxController @Inject()(
  cc: ControllerComponents
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  def index = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.index())
  }

  def image(_id: String) = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.image())
  }

  def image_files(name: String) = Action { implicit request: Request[AnyContent] =>
    import java.nio.file.{Files, Paths}

    val p = Paths.get("images", name)
    if (Files.exists(p)) { Ok.sendFile(p.toFile, true) } else { NotFound(f"Image $name not found") }
  }

  def admin = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.admin())
  }

}
