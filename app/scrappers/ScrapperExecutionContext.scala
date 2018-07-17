package soxx.scrappers

import scala.concurrent.ExecutionContext

import javax.inject._
import akka.actor.ActorSystem
import play.api.libs.concurrent.CustomExecutionContext

@Singleton
class ScrapperExecutionContext @Inject()(system: ActorSystem)
    extends CustomExecutionContext(system, "scrapper-dispatcher")
