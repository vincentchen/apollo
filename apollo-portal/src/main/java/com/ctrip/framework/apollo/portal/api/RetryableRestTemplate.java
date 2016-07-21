package com.ctrip.framework.apollo.portal.api;

import com.ctrip.framework.apollo.core.dto.ServiceDTO;
import com.ctrip.framework.apollo.core.enums.Env;
import com.ctrip.framework.apollo.core.exception.ServiceException;
import com.ctrip.framework.apollo.portal.constant.CatEventType;
import com.dianping.cat.Cat;
import com.dianping.cat.message.Message;
import com.dianping.cat.message.Transaction;

import org.apache.http.conn.ConnectTimeoutException;
import org.apache.http.conn.HttpHostConnectException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriTemplateHandler;
import org.springframework.web.util.UriTemplateHandler;

import java.net.SocketTimeoutException;
import java.util.List;

import javax.annotation.PostConstruct;

/**
 * 封装RestTemplate. admin server集群在某些机器宕机或者超时的情况下轮询重试
 */
@Component
public class RetryableRestTemplate {

  private UriTemplateHandler uriTemplateHandler = new DefaultUriTemplateHandler();

  private RestTemplate restTemplate;

  @Autowired
  private RestTemplateFactory restTemplateFactory;
  @Autowired
  private AdminServiceAddressLocator adminServiceAddressLocator;


  @PostConstruct
  private void postConstruct() {
    restTemplate = restTemplateFactory.getObject();
  }

  public <T> T get(Env env, String path, Class<T> responseType, Object... urlVariables)
      throws RestClientException {
    return execute(HttpMethod.GET, env, path, null, responseType, urlVariables);
  }

  public <T> T post(Env env, String path, Object request, Class<T> responseType, Object... uriVariables)
      throws RestClientException {
    return execute(HttpMethod.POST, env, path, request, responseType, uriVariables);
  }

  public void put(Env env, String path, Object request, Object... urlVariables) throws RestClientException {
    execute(HttpMethod.PUT, env, path, request, null, urlVariables);
  }

  public void delete(Env env, String path, Object... urlVariables) throws RestClientException {
    execute(HttpMethod.DELETE, env, path, null, null, urlVariables);
  }

  private <T> T execute(HttpMethod method, Env env, String path, Object request, Class<T> responseType,
                        Object... uriVariables) {

    String uri = uriTemplateHandler.expand(path, uriVariables).getPath();
    Transaction ct = Cat.newTransaction("AdminAPI", uri);

    List<ServiceDTO> services = adminServiceAddressLocator.getServiceList(env);

    if (CollectionUtils.isEmpty(services)) {
      ServiceException e = new ServiceException("No available admin service");
      ct.setStatus(e);
      ct.complete();
      throw e;
    }

    for (ServiceDTO serviceDTO : services) {
      try {

        T result = doExecute(method, serviceDTO, path, request, responseType, uriVariables);

        ct.setStatus(Message.SUCCESS);
        ct.complete();
        return result;
      } catch (Throwable t) {
        Cat.logError(t);
        if (canRetry(t, method)) {
          Cat.logEvent(CatEventType.API_RETRY, uri);
          continue;
        } else {//biz exception rethrow
          ct.setStatus(t);
          ct.complete();
          throw t;
        }
      }
    }

    //all admin server down
    ServiceException e = new ServiceException("No available admin service");
    ct.setStatus(e);
    ct.complete();
    throw e;
  }

  private <T> T doExecute(HttpMethod method, ServiceDTO service, String path, Object request,
                          Class<T> responseType,
                          Object... uriVariables) {
    T result = null;
    switch (method) {
      case GET:
        result = restTemplate.getForObject(parseHost(service) + path, responseType, uriVariables);
        break;
      case POST:
        result =
            restTemplate.postForEntity(parseHost(service) + path, request, responseType, uriVariables).getBody();
        break;
      case PUT:
        restTemplate.put(parseHost(service) + path, request, uriVariables);
        break;
      case DELETE:
        restTemplate.delete(parseHost(service) + path, uriVariables);
        break;
      default:
        throw new UnsupportedOperationException(String.format("not supported http method(method=%s)", method));
    }
    return result;
  }

  private String parseHost(ServiceDTO serviceAddress) {
    return serviceAddress.getHomepageUrl() + "/";
  }

  //post,delete,put请求在admin server处理超时情况下不重试
  private boolean canRetry(Throwable e, HttpMethod method) {
    Throwable nestedException = e.getCause();
    if (method == HttpMethod.GET) {
      return nestedException instanceof SocketTimeoutException
             || nestedException instanceof HttpHostConnectException
             || nestedException instanceof ConnectTimeoutException;
    } else {
      return nestedException instanceof HttpHostConnectException
             || nestedException instanceof ConnectTimeoutException;
    }
  }

}