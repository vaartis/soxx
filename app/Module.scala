import com.google.inject.AbstractModule
import play.api.{ Configuration, Environment }
import play.api.libs.concurrent.AkkaGuiceSupport

import soxx.mongowrapper.Mongo
import soxx.s3.S3Uploader
import soxx.scrappers._

class Module(_env: Environment, config: Configuration) extends AbstractModule with AkkaGuiceSupport {
  def configure = {
    bind(classOf[ScrapperExecutionContext]).asEagerSingleton()

    bind(classOf[Mongo]).asEagerSingleton()

    bindActor[ScrapperSupervisor]("scrapper-supervisor")

    if (config.get[Boolean]("soxx.s3.enabled")) {
      bindActor[S3Uploader]("s3-uploader")
    }
  }
}
