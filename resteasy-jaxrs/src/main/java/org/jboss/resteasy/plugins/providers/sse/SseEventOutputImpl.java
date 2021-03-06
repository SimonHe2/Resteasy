package org.jboss.resteasy.plugins.providers.sse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.ProcessingException;
import javax.ws.rs.ServerErrorException;
import javax.ws.rs.core.GenericType;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.sse.OutboundSseEvent;
import javax.ws.rs.sse.SseEventSink;

import org.jboss.resteasy.plugins.server.servlet.Servlet3AsyncHttpRequest;
import org.jboss.resteasy.resteasy_jaxrs.i18n.LogMessages;
import org.jboss.resteasy.resteasy_jaxrs.i18n.Messages;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.jboss.resteasy.util.HttpHeaderNames;


public class SseEventOutputImpl extends GenericType<OutboundSseEvent> implements SseEventSink
{
   private final MessageBodyWriter<OutboundSseEvent> writer;
   private final Servlet3AsyncHttpRequest request;
   private final HttpServletResponse response;
   private volatile boolean closed;
   private static final byte[] END = "\r\n\r\n".getBytes();
   private final Map<Class<?>, Object> contextDataMap;
   private boolean responseFlushed = false;
   
   public SseEventOutputImpl(final MessageBodyWriter<OutboundSseEvent> writer)
   {
      this.writer = writer; 
      contextDataMap = ResteasyProviderFactory.getContextDataMap();

      Object req = ResteasyProviderFactory.getContextData(org.jboss.resteasy.spi.HttpRequest.class);
      if (!(req instanceof Servlet3AsyncHttpRequest)) {
          throw new ServerErrorException(Messages.MESSAGES.asyncServletIsRequired(), Status.INTERNAL_SERVER_ERROR);
      }
      request = (Servlet3AsyncHttpRequest)req;

      if (!request.getAsyncContext().isSuspended()) {
         try
         {
            request.getAsyncContext().suspend();
         }
         catch (IllegalStateException ex)
         {
            LogMessages.LOGGER.failedToSetRequestAsync();
         }
      }

      response =  ResteasyProviderFactory.getContextData(HttpServletResponse.class);
   }
   
   @Override
   public synchronized void close()
   {
      if (request.getAsyncContext().isSuspended() && request.getAsyncContext().getAsyncResponse() != null) {
         if (request.getAsyncContext().isSuspended()) {
            //resume(null) will call into AbstractAsynchronousResponse.internalResume(Throwable exc)
            //The null is valid reference for Throwable:http://stackoverflow.com/questions/17576922/why-can-i-throw-null-in-java
            //Response header will be set with original one
            request.getAsyncContext().getAsyncResponse().resume(Response.noContent().build());
         }
      }
      closed = true;
   }

   protected synchronized void flushResponseToClient()
   {
      if (!responseFlushed) {
         response.setHeader(HttpHeaderNames.CONTENT_TYPE, MediaType.SERVER_SENT_EVENTS);
         //set back to client 200 OK to implies the SseEventOutput is ready
         try
         {
            response.getOutputStream().write(END);
            response.flushBuffer();
            responseFlushed = true;
         }
         catch (IOException e)
         {
            throw new ProcessingException(Messages.MESSAGES.failedToCreateSseEventOutput(), e);
         }
      }
   }
   
   @Override
   public boolean isClosed()
   {
      return closed;
   }
   
   @Override
   public CompletionStage<?> send(OutboundSseEvent event)
   {
      return send(event, (a, b) -> {});
   }

   //We need this to make it async enough
   public CompletionStage<?> send(OutboundSseEvent event, BiConsumer<SseEventSink, Throwable> errorConsumer)
   {
      flushResponseToClient();
      CompletableFuture<Object> future = CompletableFuture
            .supplyAsync(() -> {writeEvent(event); return event;});
      //TODO: log this 
      future.exceptionally((Throwable ex) -> { errorConsumer.accept(this, ex); return ex;});
      return future;
   }
   
 
   protected synchronized void writeEvent(OutboundSseEvent event)
   {
      ResteasyProviderFactory.pushContextDataMap(contextDataMap);
      try {
         if (event != null)
         {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            writer.writeTo(event, event.getClass(), null, new Annotation[]{}, event.getMediaType(), null, bout);
            response.getOutputStream().write(bout.toByteArray());
         }
         response.getOutputStream().write(END);
         response.flushBuffer();
      } catch (Exception e) {
         throw new ProcessingException(e);
      } finally {
         ResteasyProviderFactory.removeContextDataLevel();
      }
   }
}
