package io.hyperfoil.api.connection;

import java.io.IOException;

import io.netty.channel.ChannelHandlerContext;

public interface Connection {
   IOException CLOSED_EXCEPTION = new IOException("Connection was unexpectedly closed.");
   IOException SELF_CLOSED_EXCEPTION = new IOException("Connection was closed by us.");

   ChannelHandlerContext context();

   boolean isAvailable();

   int inFlight();

   void close();

   // TODO: what's the difference between close() and setClosed()?
   void setClosed();

   boolean isClosed();

   String host();

   default void onTimeout(Request request) {}
}
