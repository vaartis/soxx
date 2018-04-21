import com.google.inject.AbstractModule
import play.api.libs.concurrent.AkkaGuiceSupport

import soxx.mongowrapper.Mongo

import akka.actor._

import soxx.scrappers._
import org.mongodb.scala._

class Module extends AbstractModule with AkkaGuiceSupport {
  def configure = {
    bind(classOf[Mongo]).asEagerSingleton()

    bindActor[ScrapperSupervisor]("scrapper-supervisor")

  }
}
