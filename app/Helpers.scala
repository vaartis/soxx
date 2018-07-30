package soxx.helpers

import scala.util.control.NonFatal
import java.util.concurrent.atomic.AtomicBoolean
import scala.compat.Platform.{currentTime => now}
import scala.concurrent.duration.FiniteDuration

import play.api.mvc.{ Session, Cookie }
import resource._

object Helpers {
  // Taken from https://gist.github.com/pathikrit/79ad500a6b31f62ab4e8
  /**
    * Debounce a function i.e. it can only be invoked iff it is not already running
    * and atleast wait duration has passed since it last stopped running
    * Usage:
    *    def test(i: Int) = println(i)
    *    val f = debounce(1.second)(test)
    *    (0 to 1e9.toInt).par.map(f)
    *
    * @return a function such that it returns Some(original output) if it was invoked
    *         or else if it failed to run because of above rules, None
    */
  def debounce[A, B](wait: FiniteDuration)(f: A => B): A => Option[B] = {
    var (isRunning, lastStopTime) = (new AtomicBoolean(false), Long.MinValue)
      (input: A) => {
      val doneWaiting = lastStopTime + wait.toMillis <= now
      if (isRunning.compareAndSet(false, doneWaiting) && doneWaiting) {
        try {
          Some(f(input))
        } finally {
          lastStopTime = now
          isRunning.set(false)
        }
      } else {
        None
      }
    }
  }

  /** Read a file into an Either.
    */
  def readFile(filePath: String): Either[Seq[Throwable], String] =
    try {
      (for (cfgFile <- managed(scala.io.Source.fromFile(filePath))) yield cfgFile.mkString).either
    } catch {
      case NonFatal(e) => Left(Seq(e))
    }

  def isAdminLoggedIn(session: Session) = {
    session.get("soxx.admin.logged-in") match {
      case Some("1") => true
      case _ => false
    }
  }


  implicit class RequestHelpers(req: play.api.mvc.Request[_]) {

    /** A full host full host addres with a protocol.
      */
    def hostWithProtocol = f"""http${ if(req.secure) "s" else "" }://""" + req.host
  }
}
