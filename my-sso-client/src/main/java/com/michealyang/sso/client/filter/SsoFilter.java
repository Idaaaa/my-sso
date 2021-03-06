package com.michealyang.sso.client.filter;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.michealyang.commons.utils.CookieUtil;
import com.michealyang.commons.utils.HttpUtil;
import com.michealyang.sso.access.model.User;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Map;

/**
 * Created by michealyang on 17/5/3.
 */
public class SsoFilter  extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(SsoFilter.class);

    private static final String SESSION_KEY = "sso";

    private static final String ORIGIN_URI = "/sso/token";

    private static final String COOKIE_SSOID = "ssoid";
    private static final String COOKIE_PRE_URL = "preurl";

    private String ssoLogin;

    private String ssoAuth;

    private String host;

    public String getSsoLogin() {
        return ssoLogin;
    }

    public void setSsoLogin(String ssoLogin) {
        this.ssoLogin = ssoLogin;
    }

    public String getSsoAuth() {
        return ssoAuth;
    }

    public void setSsoAuth(String ssoAuth) {
        this.ssoAuth = ssoAuth;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        logger.info("[doFilterInternal] uri=#{}", uri);

        //1. 对于静态资源，要通过
        //2. 对于API接口，要通过
        if(uri.startsWith("/static/")
                || uri.startsWith("/api/")){
            filterChain.doFilter(request, response);
            return;
        }

        //3. 对于Ajax请求，要通过
        String requestType = request.getHeader("X-Requested-With");
        if("XMLHttpRequest".equals(requestType)){
            filterChain.doFilter(request, response);
            return;
        }

        if(ORIGIN_URI.equals(uri)) {
            if (setSession(request, response, uri)) {
                //do nothing
            } else {
                redirectToSso(request, response);
            }
            return;
        }

        if(!checkLoggingStatus(request)){
            //还未登录，或者登录状态已失效
            redirectToSso(request, response);
        }else {
            //已经登录过，并且未超时失效
            Cookie cookie = WebUtils.getCookie(request, COOKIE_SSOID);
            if(cookie == null || StringUtils.isBlank(cookie.getValue())){
                redirectToSso(request, response);
            }else {
                //刷新登录状态时效
                goOn(request, response, cookie.getValue());
            }
        }
        filterChain.doFilter(request, response);
    }

    private boolean setSession(HttpServletRequest request,
                               HttpServletResponse response,
                               String uri) throws IOException {
        Preconditions.checkArgument(uri != null);
        String token = HttpUtil.getParameterValue(request, "token");
        if (StringUtils.isBlank(token)){
            logger.error("[doFilterInternal] token is empty");
            return false;
        }
        return goOn(request, response, token);
    }

    private boolean checkLoggingStatus(HttpServletRequest request) {
        User user = (User) WebUtils.getSessionAttribute(request, SESSION_KEY);
        if(user != null) {
            return true;
        }
        return false;
    }

    private void redirectToSso(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String preUrl = request.getRequestURL().toString();
        String localHost = HttpUtil.getHost(preUrl, request.getRequestURI());
        CookieUtil.setCookie(response, COOKIE_PRE_URL, preUrl);
        String targetUrl = ssoLogin + "?origin=" + HttpUtil.formatUrl(host, ORIGIN_URI);;
        response.sendRedirect(targetUrl);
    }

    /**
     * auth and set session
     * @return
     */
    private boolean goOn(HttpServletRequest request,
                         HttpServletResponse response,
                         String token) throws IOException {
        if(!auth(request, response, token)) {
            logger.info("[goOn] auth failure. token=#{}", token);
            //认证失败，重新登录
            redirectToSso(request, response);
            return false;
        }else {
            logger.info("[goOn] auth success. token=#{}", token);
            CookieUtil.setCookie(response, COOKIE_SSOID, token);
            if(ORIGIN_URI.equals(request.getRequestURI())){
                CookieUtil.removeCookie(request, response, COOKIE_PRE_URL);
                response.sendRedirect("/");
                return true;
            }
            String preUrl = CookieUtil.getCookieValue(request, COOKIE_PRE_URL);
            if(StringUtils.isNotBlank(preUrl)) {
                CookieUtil.removeCookie(request, response, COOKIE_PRE_URL);
                response.sendRedirect(preUrl);
                return true;
            }
        }
        return true;
    }

    private boolean auth(HttpServletRequest request,
                         HttpServletResponse response,
                         String token) {

        if(StringUtils.isBlank(token)) return false;
        String res = HttpUtil.doGetWithOkHttp(ssoAuth + "?token=" + token);
        logger.info("[auth] res=#{}", res);
        if(StringUtils.isBlank(res)) {
            logger.error("[auth] authentication failed");
            return false;
        }
        try {
            JSONObject jsonObject = JSON.parseObject(res);
            if(jsonObject == null) {
                logger.error("[auth] invalid json format");
                return false;
            }
            Integer code = jsonObject.getInteger("code");
            if(code == null || code != 1) {
                logger.warn("[auth] authentication returned failure msg. json=#{}", jsonObject);
                return false;
            }
            JSONObject data = (JSONObject)jsonObject.get("data");
            if(data == null) {
                logger.error("[auth] data is missing. data=#{}", data);
                return false;
            }
            JSONObject userJson = (JSONObject)data.get("user");
            if(userJson == null) {
                logger.error("[auth] user is missing. userJson=#{}", userJson);
                return false;
            }
            Long id = userJson.getLong("id");
            String userName = userJson.getString("userName");
            if(id == null || StringUtils.isBlank(userName)) {
                logger.error("[auth] user info is missing. json=#{}", jsonObject);
                return false;
            }
            User user = new User();
            user.setId((int)(long)id);
            user.setUserName(userName);
            HttpSession session = request.getSession(true);
            session.setAttribute(SESSION_KEY, user);
        }catch (Exception e){
            logger.error("[auth] string parsed to json failed. e=#{}", e);
            return false;
        }
        return true;
    }

    private String getParamter(String uri, String key){
        Preconditions.checkArgument(StringUtils.isNotBlank(uri));
        Preconditions.checkArgument(StringUtils.isNotBlank(key));
        Map<String, String> params = Splitter.on("&").trimResults().withKeyValueSeparator("=").split(uri);
        if(CollectionUtils.isEmpty(params)) {
            return null;
        }
        return params.get(key);
    }

    public static void main(String[] args) {
        String uri = "token=9bc79d62bdc22e07b5b56a8e71021e08&a=2";
        Map<String, String> params = Splitter.on("&").trimResults().withKeyValueSeparator("=").split(uri);
        System.out.println(params);
    }
}
