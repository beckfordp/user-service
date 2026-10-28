package userservice

import purekafka.docs.{
  AsyncApiChannel,
  AsyncApiChannelsProvider,
  ChannelDirection,
  PublisherKind
}

/** Kafka contracts for AsyncAPI documentation (ADR 0010). `UserRegistered` is
  * published via the outbox/CDC path (ADR 0008), hence `Publish(Cdc)`.
  */
object AsyncApiChannels extends AsyncApiChannelsProvider {
  val channels: List[AsyncApiChannel[?]] = List(
    AsyncApiChannel[UserRegisteredEvent](
      topic = "user-events",
      eventType = "UserRegistered",
      direction = ChannelDirection.Publish(PublisherKind.Cdc)
    )
  )
}
