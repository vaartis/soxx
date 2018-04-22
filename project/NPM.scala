import java.net.InetSocketAddress
import play.sbt.PlayRunHook
import scala.sys.process.Process
import sbt._

object NPM {
  def apply(base: File): PlayRunHook = {
    object NPMHook extends PlayRunHook {
      var process: Option[Process] = None

      override def beforeStarted() = {
        process = Option(
          Process("npm run watch", base).run()
        )
      }

      override def afterStopped() = {
        process.foreach(_.destroy())
        process = None
      }
    }

    NPMHook

  }
}
