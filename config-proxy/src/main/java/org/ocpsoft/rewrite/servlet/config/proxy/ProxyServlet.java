package org.ocpsoft.rewrite.servlet.config.proxy;

/**
 * Copyright MITRE
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.BitSet;
import java.util.Enumeration;
import java.util.Formatter;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.message.HeaderGroup;
import org.apache.http.util.EntityUtils;
import org.ocpsoft.logging.Logger;

/**
 * An HTTP reverse proxy/gateway servlet. It is designed to be extended for customization if desired. Most of the work
 * is handled by <a href="http://hc.apache.org/httpcomponents-client-ga/">Apache HttpClient</a>.
 * <p>
 * There are alternatives to a servlet based proxy such as Apache mod_proxy if that is available to you. However this
 * servlet is easily customizable by Java, secure-able by your web application's security (e.g. spring-security),
 * portable across servlet engines, and is embeddable into another web application.
 * </p>
 * <p>
 * Inspiration: http://httpd.apache.org/docs/2.0/mod/mod_proxy.html
 * </p>
 * 
 * @author David Smiley dsmiley@mitre.org
 */
public class ProxyServlet
{
   /* INIT PARAMETER NAME CONSTANTS */

   // FIXME:  determine if this should be serializable
   // if so, implement interface; if not, remove serialVersionUID
   @SuppressWarnings("unused")
   private static final long serialVersionUID = -362164247914670579L;

   /**
    * A boolean parameter name to enable logging of input and target URLs to the servlet log.
    */
   public static final String P_LOG = "log";

   /**
    * The parameter name for the target (destination) URI to proxy to.
    */
   private static final String P_TARGET_URI = "targetUri";

   /* MISC */

   protected boolean doLog = false;
   protected URI targetUriObj;
   protected String targetUri;
   protected CloseableHttpClient proxyClient;

   private ServletConfig servletConfig;

   private static final Logger logger = Logger.getLogger(ProxyServlet.class);

   public ServletConfig getServletConfig()
   {
      return servletConfig;
   }

   public void init(ServletConfig servletConfig) throws ServletException
   {
      this.servletConfig = servletConfig;
      String doLogStr = servletConfig.getInitParameter(P_LOG);
      if (doLogStr != null)
      {
         this.doLog = Boolean.parseBoolean(doLogStr);
      }

      try
      {
         targetUriObj = new URI(servletConfig.getInitParameter(P_TARGET_URI));
      }
      catch (Exception e)
      {
         throw new RuntimeException("Trying to process targetUri init parameter: " + e, e);
      }
      targetUri = targetUriObj.toString();

      proxyClient = HttpClientBuilder.create().build();
   }

   public void destroy()
   {
      try
      {
         if (proxyClient != null) proxyClient.close();
      }
      catch (IOException e)
      {
         logger.error("The proxyClient threw an exception on closing.", e);
      }
   }

   protected void service(HttpServletRequest servletRequest, HttpServletResponse servletResponse)
            throws ServletException, IOException
   {
      /*
       * Note: we won't transfer the protocol version because I'm not sure it would truly be compatible
       */
      String method = servletRequest.getMethod();
      HttpRequest proxyRequest;
      /*
       * Spec: RFC 2616, sec 4.3: either of these two headers signal that there is a message body.
       */
      if (servletRequest.getHeader(HttpHeaders.CONTENT_LENGTH) != null ||
               servletRequest.getHeader(HttpHeaders.TRANSFER_ENCODING) != null)
      {
         HttpEntityEnclosingRequest eProxyRequest = new BasicHttpEntityEnclosingRequest(method, targetUri);
         /*
          * Add the input entity (streamed) note: we don't bother ensuring we close the servletInputStream since the
          * container handles it
          */
         eProxyRequest.setEntity(new InputStreamEntity(servletRequest.getInputStream(), servletRequest
                  .getContentLength()));
         proxyRequest = eProxyRequest;
      }
      else
         proxyRequest = new BasicHttpRequest(method, targetUri);

      copyRequestHeaders(servletRequest, proxyRequest);

      try
      {
         /*
          * Execute the request
          */
         if (doLog)
         {
            logger.debug("proxy " + method + " uri: " + servletRequest.getRequestURI() + " -- "
                     + proxyRequest.getRequestLine().getUri());
         }
         HttpResponse proxyResponse = proxyClient.execute(URIUtils.extractHost(targetUriObj), proxyRequest);

         /*
          * Process the response
          */
         int statusCode = proxyResponse.getStatusLine().getStatusCode();

         if (doResponseRedirectOrNotModifiedLogic(servletRequest, servletResponse, proxyResponse, statusCode))
         {
            /*
             * just to be sure, but is probably a no-op
             */
            EntityUtils.consume(proxyResponse.getEntity());
            return;
         }

         setResponseStatus(servletResponse, statusCode, proxyResponse.getStatusLine().getReasonPhrase());

         copyResponseHeaders(proxyResponse, servletResponse);

         /*
          * Send the content to the client
          */
         copyResponseEntity(proxyResponse, servletResponse);

      }
      catch (Exception e)
      {
         if (e instanceof RuntimeException)
            throw (RuntimeException) e;
         if (e instanceof ServletException)
            throw (ServletException) e;
         // noinspection ConstantConditions
         if (e instanceof IOException)
            throw (IOException) e;
         throw new RuntimeException(e);
      }
   }

   @SuppressWarnings("deprecation")
   private void setResponseStatus(HttpServletResponse servletResponse, int statusCode, String reason) {
       /*
        * Pass the response code. This method with the "reason phrase" is deprecated but it's the only way to pass the
        * reason along too. noinspection deprecation
        */
       servletResponse.setStatus(statusCode, reason);
   }

   protected boolean doResponseRedirectOrNotModifiedLogic(
            HttpServletRequest servletRequest, HttpServletResponse servletResponse,
            HttpResponse proxyResponse, int statusCode)
            throws ServletException, IOException
   {
      /*
       * Check if the proxy response is a redirect. The following code is adapted from
       * org.tigris.noodle.filters.CheckForRedirect
       */
      if (statusCode >= HttpServletResponse.SC_MULTIPLE_CHOICES /* 300 */
               && statusCode < HttpServletResponse.SC_NOT_MODIFIED /* 304 */)
      {
         Header locationHeader = proxyResponse.getLastHeader(HttpHeaders.LOCATION);
         if (locationHeader == null)
         {
            throw new ServletException("Received status code: " + statusCode
                     + " but no " + HttpHeaders.LOCATION + " header was found in the response");
         }
         /*
          * Modify the redirect to go to this proxy servlet rather that the proxied host
          */
         String locStr = rewriteUrlFromResponse(servletRequest, locationHeader.getValue());

         servletResponse.sendRedirect(locStr);
         return true;
      }

      /*
       * 304 needs special handling. See: http://www.ics.uci.edu/pub/ietf/http/rfc1945.html#Code304 . We get a 304
       * whenever passed an 'If-Modified-Since' header and the data on disk has not changed; server responds w/ a 304
       * saying I'm not going to send the body because the file has not changed.
       */
      if (statusCode == HttpServletResponse.SC_NOT_MODIFIED)
      {
         servletResponse.setIntHeader(HttpHeaders.CONTENT_LENGTH, 0);
         servletResponse.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
         return true;
      }
      return false;
   }

   protected void closeQuietly(Closeable closeable)
   {
      try
      {
         closeable.close();
      }
      catch (IOException e)
      {
         logger.warn(e.getMessage(), e);
      }
   }

   /**
    * These are the "hop-by-hop" headers that should not be copied.
    * http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html I use an HttpClient HeaderGroup class instead of
    * Set<String> because this approach does case insensitive lookup faster.
    */
   protected static final HeaderGroup hopByHopHeaders;
   static
   {
      hopByHopHeaders = new HeaderGroup();
      String[] headers = new String[] {
               "Connection", "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization",
               "TE", "Trailers", "Transfer-Encoding", "Upgrade" };
      for (String header : headers)
      {
         hopByHopHeaders.addHeader(new BasicHeader(header, null));
      }
   }

   /** Copy request headers from the servlet client to the proxy request. */
   protected void copyRequestHeaders(HttpServletRequest servletRequest, HttpRequest proxyRequest)
   {
      /*
       * Get an Enumeration of all of the header names sent by the client
       */
      Enumeration<?> enumerationOfHeaderNames = servletRequest.getHeaderNames();
      while (enumerationOfHeaderNames.hasMoreElements())
      {
         String headerName = (String) enumerationOfHeaderNames.nextElement();
         // Instead the content-length is effectively set via InputStreamEntity
         if (headerName.equalsIgnoreCase(HttpHeaders.CONTENT_LENGTH))
            continue;
         if (hopByHopHeaders.containsHeader(headerName))
            continue;

         Enumeration<?> headers = servletRequest.getHeaders(headerName);
         while (headers.hasMoreElements())
         {
            /*
             * sometimes more than one value
             */
            String headerValue = (String) headers.nextElement();
            /*
             * In case the proxy host is running multiple virtual servers, rewrite the Host header to ensure that we get
             * content from the correct virtual server
             */
            if (headerName.equalsIgnoreCase(HttpHeaders.HOST))
            {
               HttpHost host = URIUtils.extractHost(this.targetUriObj);
               headerValue = host.getHostName();
               if (host.getPort() != -1)
                  headerValue += ":" + host.getPort();
            }
            proxyRequest.addHeader(headerName, headerValue);
         }
      }
   }

   /**
    * Copy proxied response headers back to the servlet client.
    */
   protected void copyResponseHeaders(HttpResponse proxyResponse, HttpServletResponse servletResponse)
   {
      for (Header header : proxyResponse.getAllHeaders())
      {
         if (hopByHopHeaders.containsHeader(header.getName()))
            continue;
         servletResponse.addHeader(header.getName(), header.getValue());
      }
   }

   /**
    * Copy response body data (the entity) from the proxy to the servlet client.
    */
   protected void copyResponseEntity(HttpResponse proxyResponse, HttpServletResponse servletResponse)
            throws IOException
   {
      HttpEntity entity = proxyResponse.getEntity();
      if (entity != null)
      {
         OutputStream servletOutputStream = servletResponse.getOutputStream();
         try
         {
            entity.writeTo(servletOutputStream);
         }
         finally
         {
            closeQuietly(servletOutputStream);
         }
      }
   }

   /**
    * For a redirect response from the target server, this translates {@code theUrl} to redirect to and translates it to
    * one the original client can use.
    */
   protected String rewriteUrlFromResponse(HttpServletRequest servletRequest, String theUrl)
   {
      /*
       * TODO document example paths
       */
      if (theUrl.startsWith(targetUri))
      {
         String curUrl = servletRequest.getRequestURL().toString();// no query
         String pathInfo = servletRequest.getPathInfo();
         if (pathInfo != null)
         {
            assert curUrl.endsWith(pathInfo);
            curUrl = curUrl.substring(0, curUrl.length() - pathInfo.length());// take pathInfo off
         }
         theUrl = curUrl + theUrl.substring(targetUri.length());
      }
      return theUrl;
   }

   /**
    * Encodes characters in the query or fragment part of the URI.
    * 
    * <p>
    * Unfortunately, an incoming URI sometimes has characters disallowed by the spec. HttpClient insists that the
    * outgoing proxied request has a valid URI because it uses Java's {@link URI}. To be more forgiving, we must escape
    * the problematic characters. See the URI class for the spec.
    * 
    * @param in example: name=value&foo=bar#fragment
    */
   protected static CharSequence encodeUriQuery(CharSequence in)
   {
      /*
       * Note that I can't simply use URI.java to encode because it will escape pre-existing escaped things. TODO:
       * replace/compare to with Rewrite Encoding
       */
      StringBuilder outBuf = null;
      Formatter formatter = null;
      for (int i = 0; i < in.length(); i++)
      {
         char c = in.charAt(i);
         boolean escape = true;
         if (c < 128)
         {
            if (asciiQueryChars.get(c))
            {
               escape = false;
            }
         }
         else if (!Character.isISOControl(c) && !Character.isSpaceChar(c))
         {
            /*
             * not-ascii
             */
            escape = false;
         }

         if (!escape)
         {
            if (outBuf != null)
               outBuf.append(c);
         }
         else
         {
            /*
             * escape
             */
            if (outBuf == null)
            {
               outBuf = new StringBuilder(in.length() + 5 * 3);
               outBuf.append(in, 0, i);
               formatter = new Formatter(outBuf);
            }
            /*
             * leading %, 0 padded, width 2, capital hex
             */
            formatter.format("%%%02X", (int) c);// TODO
         }
      }

      return outBuf != null ? outBuf : in;
   }

   protected static final BitSet asciiQueryChars;
   static
   {
      char[] c_unreserved = "_-!.~'()*".toCharArray();// plus alphanum
      char[] c_punct = ",;:$&+=".toCharArray();
      char[] c_reserved = "?/[]@".toCharArray();// plus punct

      asciiQueryChars = new BitSet(128);
      for (char c = 'a'; c <= 'z'; c++)
         asciiQueryChars.set(c);
      for (char c = 'A'; c <= 'Z'; c++)
         asciiQueryChars.set(c);
      for (char c = '0'; c <= '9'; c++)
         asciiQueryChars.set(c);
      for (char c : c_unreserved)
         asciiQueryChars.set(c);
      for (char c : c_punct)
         asciiQueryChars.set(c);
      for (char c : c_reserved)
         asciiQueryChars.set(c);

      asciiQueryChars.set('%');
      /*
       * leave existing percent escapes in place
       */
   }

}
