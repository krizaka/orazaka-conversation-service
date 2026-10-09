package com.krizaka.orazaka.conversationservice.infrastructure.config;

import com.krizaka.orazaka.persistence.infrastructure.config.MessagingContract;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topology for relaying wallet events to connected browsers.
 *
 * <p><b>An anonymous queue per instance</b>, which is the whole design decision here. A user's SSE
 * emitter lives on exactly one conversation-service instance, so competing consumers on a shared
 * queue would hand the event to whichever instance happened to read it — usually not the one
 * holding that user's connection, and the update would be silently dropped. Fan-out to every
 * instance means the one that matters always gets it and the rest discard a no-op.
 *
 * <p>{@link AnonymousQueue} rather than a name built from {@code spring.application.name}: two
 * instances of this service share that name, and would end up sharing the queue — reintroducing
 * exactly the competing-consumer problem. The broker generates a unique name per connection.
 *
 * <p>Exclusive and auto-delete, so a queue never outlives the instance whose emitters it was
 * feeding. These carry display state; losing one on restart costs a stale balance until the next
 * event, which the client also reconciles on load.
 */
@Configuration
public class WalletEventsTopologyConfig {

  /** Both the adjustment announcement and the low-balance warning. */
  private static final String WALLET_BINDING = "evt.wallet.*";

  private static final String CREDIT_BINDING = "evt.credit.*";

  @Bean
  public TopicExchange walletEventsExchange() {
    return new TopicExchange(MessagingContract.EVENTS_EXCHANGE, true, false);
  }

  @Bean
  public Queue walletEventsQueue() {
    return new AnonymousQueue();
  }

  @Bean
  public Binding walletEventsBinding(Queue walletEventsQueue, TopicExchange walletEventsExchange) {
    return BindingBuilder.bind(walletEventsQueue).to(walletEventsExchange).with(WALLET_BINDING);
  }

  @Bean
  public Binding creditEventsBinding(Queue walletEventsQueue, TopicExchange walletEventsExchange) {
    return BindingBuilder.bind(walletEventsQueue).to(walletEventsExchange).with(CREDIT_BINDING);
  }
}
