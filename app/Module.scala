import com.google.inject.AbstractModule
import play.api.libs.concurrent.AkkaGuiceSupport

import soxx.mongowrapper.Mongo
import soxx.scrappers._

class Module extends AbstractModule with AkkaGuiceSupport {
  def configure = {
    bind(classOf[Mongo]).asEagerSingleton()

    bindActor[ScrapperSupervisor]("scrapper-supervisor")
  }
}
