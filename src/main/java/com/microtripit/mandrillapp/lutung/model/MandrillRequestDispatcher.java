/**
 * 
 */
package com.microtripit.mandrillapp.lutung.model;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;

import com.microtripit.mandrillapp.lutung.logging.Logger;
import com.microtripit.mandrillapp.lutung.logging.LoggerFactory;
import com.microtripit.mandrillapp.lutung.model.MandrillApiError.MandrillError;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import javax.net.ssl.SSLContext;

/**
 * @author rschreijer
 * @since Feb 21, 2013
 */
public final class MandrillRequestDispatcher {
    private static final Logger log = LoggerFactory.getLogger(MandrillRequestDispatcher.class);

	/**
	 * See https://hc.apache.org/httpcomponents-core-4.3.x/httpcore/apidocs/org/apache/http/params/HttpConnectionParams.html#setSoTimeout(org.apache.http.params.HttpParams, int)
	 *
	 * A value of 0 means no timeout at all.
	 * The value is expressed in milliseconds.
	 * */
	public static int SOCKET_TIMEOUT_MILLIS = 0;

	/**
	 * See https://hc.apache.org/httpcomponents-core-4.3.x/httpcore/apidocs/org/apache/http/params/HttpConnectionParams.html#setConnectionTimeout(org.apache.http.params.HttpParams, int)
	 *
	 * A value of 0 means no timeout at all.
	 * The value is expressed in milliseconds.
	 * */
	public static int CONNECTION_TIMEOUT_MILLIS = 0;
	
	
	private static CloseableHttpClient httpClient;
	private static PoolingHttpClientConnectionManager connexionManager;
	private static RequestConfig defaultRequestConfig;
	private static SSLContext sslContext;
	private static SSLConnectionSocketFactory sslSocketFactory;
	private static Registry<ConnectionSocketFactory> socketFactoryRegistry;
	
	static {
		defaultRequestConfig = RequestConfig.custom()
				.setSocketTimeout(SOCKET_TIMEOUT_MILLIS)
				.setConnectTimeout(CONNECTION_TIMEOUT_MILLIS)
				.setConnectionRequestTimeout(CONNECTION_TIMEOUT_MILLIS).build();
		
		sslContext = createSSLContext();
		sslSocketFactory = new SSLConnectionSocketFactory(
			 	sslContext,
                new String[] { "TLSv1.2" },
                null,
                new DefaultHostnameVerifier());
		
		socketFactoryRegistry = RegistryBuilder
		        .<ConnectionSocketFactory> create().register("https", sslSocketFactory)
		        .build();
		
		connexionManager = new PoolingHttpClientConnectionManager(socketFactoryRegistry);
		connexionManager.setDefaultMaxPerRoute(50);
		
		
		httpClient = HttpClientBuilder.create()
				.setUserAgent("/Lutung-0.1")
				.setConnectionManager(connexionManager)
				.setDefaultRequestConfig(defaultRequestConfig)
				.setSSLSocketFactory(sslSocketFactory)
				.build();		
	}

	public static final <T> T execute(final RequestModel<T> requestModel) throws MandrillApiError, IOException {

		HttpResponse response = null;
		String responseString = null;
		try {
			// use proxy?
			final ProxyData proxyData = detectProxyServer(requestModel.getUrl());
			if (proxyData != null) {
				if (log.isDebugEnabled()) {
					log.debug(String.format("Using proxy @" + proxyData.host
							+ ":" + String.valueOf(proxyData.port)));
				}
				final HttpHost proxy = new HttpHost(proxyData.host,
						proxyData.port);
				httpClient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY,
						proxy);
			}
            log.debug("starting request '" +requestModel.getUrl()+ "'");
			response = httpClient.execute( requestModel.getRequest() );
			final StatusLine status = response.getStatusLine();
			responseString = EntityUtils.toString(response.getEntity());
			if( requestModel.validateResponseStatus(status.getStatusCode()) ) {
				try {
					return requestModel.handleResponse( responseString );
					
				} catch(final HandleResponseException e) {
					throw new IOException(
							"Failed to parse response from request '" 
							+requestModel.getUrl()+ "'", e);
					
				}
				
			} else {
				// ==> compile mandrill error!
				MandrillError error = null;
				try {
				    error = LutungGsonUtils.getGson()
						.fromJson(responseString, MandrillError.class);
				} catch (Throwable ex) {
				    error = new MandrillError("Invalid Error Format",
				                              "Invalid Error Format",
				                              responseString,
				                              status.getStatusCode());
				}

				throw new MandrillApiError(
						"Unexpected http status in response: " 
						+status.getStatusCode()+ " (" 
						+status.getReasonPhrase()+ ")").withError(error);
			}
				
		} finally {
			try {
				EntityUtils.consume(response.getEntity());
			} catch (IOException e) {
				log.error("Error consuming entity", e);
				throw e;
			}
		}
	}

    private static final ProxyData detectProxyServer(final String url) {
        try {
            final List<Proxy> proxies = ProxySelector.getDefault().select(new URI(url));
            if(proxies != null) {
                for(Proxy proxy : proxies) {
                    InetSocketAddress addr = (InetSocketAddress) proxy.address();
                    if(addr != null) {
                        return new ProxyData(addr.getHostName(), addr.getPort());
                    }
                }
            }
            // no proxy detected!
            return null;

        } catch (final Throwable t) {
            log.error("Error detecting proxy server", t);
            return null;

        }
    }

    private static SSLContext createSSLContext() {
		SSLContext sslContext = null;
    	try{
			sslContext = SSLContext.getInstance("TLSv1.2");
			sslContext.init(null, null, null);
		}catch(NoSuchAlgorithmException e){
			e.printStackTrace();
		}catch (KeyManagementException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return sslContext;
	}
    
    private static final class ProxyData {
        String host;
        int port;

        protected ProxyData(final String host, final int port) {
            this.host = host;
            this.port = port;
        }

    }

}
