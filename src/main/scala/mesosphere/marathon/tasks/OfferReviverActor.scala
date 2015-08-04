package mesosphere.marathon.tasks

import akka.actor.{ Props, Cancellable, ActorLogging, Actor }
import akka.event.LoggingReceive
import mesosphere.marathon.MarathonSchedulerDriverHolder
import mesosphere.marathon.state.Timestamp
import scala.concurrent.duration._

object OfferReviverActor {
  final val NAME = "offerReviver"

  def props(conf: OfferReviverConf, driverHolder: MarathonSchedulerDriverHolder): Props = {
    Props(new OfferReviverActor(conf, driverHolder))
  }
}

/**
  * Revive offers whenever interest is signaled but maximally every 5 seconds.
  */
private class OfferReviverActor(
    conf: OfferReviverConf,
    driverHolder: MarathonSchedulerDriverHolder) extends Actor with ActorLogging {
  private[this] var lastRevive: Timestamp = Timestamp(0)
  private[this] var nextReviveCancellableOpt: Option[Cancellable] = None

  override def postStop(): Unit = {
    nextReviveCancellableOpt.foreach(_.cancel())
    nextReviveCancellableOpt = None
  }

  private[this] def reviveOffers(): Unit = {
    val now: Timestamp = Timestamp.now()
    val nextRevive = lastRevive + conf.minReviveOffersInterval().milliseconds

    if (nextRevive <= now) {
      log.info("Cancel any scheduled revive and revive offers now")
      nextReviveCancellableOpt.foreach(_.cancel())
      nextReviveCancellableOpt = None

      driverHolder.driver.foreach(_.reviveOffers())
      lastRevive = now
    }
    else {
      lazy val untilNextRevive = now until nextRevive
      if (nextReviveCancellableOpt.isEmpty) {
        log.info("Schedule next revive at {} in {}", nextRevive, untilNextRevive)
        nextReviveCancellableOpt = Some(schedulerCheck(untilNextRevive))
      }
      else if (log.isDebugEnabled) {
        log.debug("next revive at {} not yet due for {}, ignore", nextRevive, untilNextRevive)
      }
    }
  }

  override def receive: Receive = LoggingReceive {
    case OfferReviverDelegate.ReviveOffers => reviveOffers()
  }

  protected def schedulerCheck(duration: FiniteDuration): Cancellable = {
    import context.dispatcher
    context.system.scheduler.scheduleOnce(duration, self, OfferReviverDelegate.ReviveOffers)
  }
}