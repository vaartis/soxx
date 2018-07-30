package controllers

import scala.concurrent._
import javax.inject._

import play.api.Configuration
import play.api.mvc._
import play.api.data._
import play.api.data.Forms._

import soxx.helpers.Helpers

@Singleton
class SoxxController @Inject()(
  cc: ControllerComponents
)(implicit ec: ExecutionContext, config: Configuration) extends AbstractController(cc) {

  def index = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.index())
  }

  def image(_id: String) = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.image())
  }

  def image_files(name: String) = Action { implicit request: Request[AnyContent] =>
    import java.nio.file.{Files, Paths}

    val p = Paths.get(config.get[String]("soxx.scrappers.downloadDirectory"), name)
    if (Files.exists(p)) { Ok.sendFile(p.toFile, true) } else { NotFound(f"Image $name not found") }
  }

  def admin_login_page = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.admin_login())
  }

  def admin_login = Action { implicit request: Request[AnyContent] =>
    case class AdminLoginForm(pass: String)
    val loginForm = Form(
      mapping(
        "pass" -> nonEmptyText,
      )(AdminLoginForm.apply)(AdminLoginForm.unapply _)
    )

    loginForm
      .bindFromRequest
      .fold(
        _ => BadRequest("Need a 'pass' field to log in"),
        { loginData =>
          if (config.get[String]("soxx.admin.password") == loginData.pass) {
            Redirect(routes.SoxxController.admin)
              .withSession(request.session +
                ("soxx.admin.logged-in" -> "1")
              )
          } else {
            Redirect(routes.SoxxController.admin_login)
          }
        }
      )
  }

  def admin = Action { implicit request: Request[AnyContent] =>
    if (Helpers.isAdminLoggedIn(request.session)) { Ok(views.html.admin()) } else { Redirect(routes.SoxxController.admin_login_page) }
  }
}
