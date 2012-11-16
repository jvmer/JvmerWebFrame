package com.jvmer.frame.web.servlet.mvc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import net.sf.json.JSONObject;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.config.BeanExpressionContext;
import org.springframework.beans.factory.config.BeanExpressionResolver;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.ClassUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.PathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.validation.support.BindingAwareModelMap;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.HttpSessionRequiredException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestDataBinder;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.SessionAttributes;
import org.springframework.web.bind.annotation.support.HandlerMethodInvoker;
import org.springframework.web.bind.annotation.support.HandlerMethodResolver;
import org.springframework.web.bind.support.SessionAttributeStore;
import org.springframework.web.bind.support.WebArgumentResolver;
import org.springframework.web.bind.support.WebBindingInitializer;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestScope;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.HandlerAdapter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.View;
import org.springframework.web.servlet.mvc.annotation.ModelAndViewResolver;
import org.springframework.web.servlet.mvc.multiaction.InternalPathMethodNameResolver;
import org.springframework.web.servlet.mvc.multiaction.MethodNameResolver;
import org.springframework.web.servlet.mvc.multiaction.NoSuchRequestHandlingMethodException;
import org.springframework.web.servlet.support.RequestContextUtils;
import org.springframework.web.servlet.support.WebContentGenerator;
import org.springframework.web.util.UrlPathHelper;
import org.springframework.web.util.WebUtils;

public class MethodHandlerAdapter extends WebContentGenerator implements HandlerAdapter, BeanFactoryAware, Ordered {
	private ConfigurableBeanFactory beanFactory;
	private BeanExpressionContext expressionContext;
	private int order = Ordered.LOWEST_PRECEDENCE;
	private final Map<Class<?>, ServletHandlerMethodResolver> methodResolverCache = new ConcurrentHashMap<Class<?>, ServletHandlerMethodResolver>();
	private int cacheSecondsForSessionAttributeHandlers = 0;
	private boolean synchronizeOnSession = false;
	private UrlPathHelper urlPathHelper = new UrlPathHelper();
	private WebBindingInitializer webBindingInitializer;
	private SessionAttributeStore sessionAttributeStore;
	private ParameterNameDiscoverer parameterNameDiscoverer;
	private WebArgumentResolver[] customArgumentResolvers;
	private HttpMessageConverter<?>[] messageConverters = getDefaultMessageConverters();
	private ModelAndViewResolver[] customModelAndViewResolvers;
	private PathMatcher pathMatcher = new AntPathMatcher();
	private MethodNameResolver methodNameResolver = new InternalPathMethodNameResolver();
	public static final Charset DEFAULT_CHARSET = Charset.forName("UTF-8");

	@Override
	public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
		if (beanFactory instanceof ConfigurableBeanFactory) {
			this.beanFactory = (ConfigurableBeanFactory) beanFactory;
			this.expressionContext = new BeanExpressionContext(this.beanFactory, new RequestScope());
		}
	}

	private HttpMessageConverter<?>[] getDefaultMessageConverters() {
		Set<HttpMessageConverter<?>> converters = new HashSet<HttpMessageConverter<?>>();
		//TODO:
		//converters.add(new MappingJacksonHttpMessageConverter());
		return converters.toArray(new HttpMessageConverter<?>[0]);
	}

	@Override
	public int getOrder() {
		return order;
	}

	@Override
	public boolean supports(Object handler) {
		return getMethodResolver(handler).hasHandlerMethods();
	}

	@Override
	public ModelAndView handle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		if (AnnotationUtils.findAnnotation(handler.getClass(), SessionAttributes.class) != null) {
			checkAndPrepare(request, response, this.cacheSecondsForSessionAttributeHandlers, true);
		} else {
			checkAndPrepare(request, response, true);
		}

		if (this.synchronizeOnSession) {
			HttpSession session = request.getSession(false);
			if (session != null) {
				Object mutex = WebUtils.getSessionMutex(session);
				synchronized (mutex) {
					return invokeHandlerMethod(request, response, handler);
				}
			}
		}

		return invokeHandlerMethod(request, response, handler);
	}

	@Override
	public long getLastModified(HttpServletRequest request, Object handler) {
		return -1;
	}

	/**
	 * Build a HandlerMethodResolver for the given handler type.
	 */
	protected ServletHandlerMethodResolver getMethodResolver(Object handler) {
		Class<?> handlerClass = ClassUtils.getUserClass(handler);
		ServletHandlerMethodResolver resolver = this.methodResolverCache.get(handlerClass);
		if (resolver == null) {
			resolver = new ServletHandlerMethodResolver(handlerClass);
			this.methodResolverCache.put(handlerClass, resolver);
		}
		return resolver;
	}

	protected ModelAndView invokeHandlerMethod(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
		ServletHandlerMethodResolver methodResolver = getMethodResolver(handler);
		Method handlerMethod = methodResolver.resolveHandlerMethod(request);
		ServletHandlerMethodInvoker methodInvoker = new ServletHandlerMethodInvoker(methodResolver);
		ServletWebRequest webRequest = new ServletWebRequest(request, response);
		ExtendedModelMap implicitModel = new BindingAwareModelMap();

		Object result = methodInvoker.invokeHandlerMethod(handlerMethod, handler, webRequest, implicitModel);
		ModelAndView mav = methodInvoker.getModelAndView(handlerMethod, handler.getClass(), result, implicitModel, webRequest);
		methodInvoker.updateModelAttributes(handler, (mav != null ? mav.getModel() : null), implicitModel, webRequest);
		return mav;
	}
	
	protected ServletRequestDataBinder createBinder(HttpServletRequest request, Object target, String objectName)
			throws Exception {
		return new ServletRequestDataBinder(target, objectName);
	}
	
	/**
	 * Template method for creating a new HttpInputMessage instance.
	 * <p>The default implementation creates a standard {@link ServletServerHttpRequest}.
	 * This can be overridden for custom {@code HttpInputMessage} implementations
	 * @param servletRequest current HTTP request
	 * @return the HttpInputMessage instance to use
	 * @throws Exception in case of errors
	 */
    protected HttpInputMessage createHttpInputMessage(HttpServletRequest servletRequest) throws Exception {
		return new ServletServerHttpRequest(servletRequest);
	}
    
    /**
	 * Template method for creating a new HttpOuputMessage instance.
	 * <p>The default implementation creates a standard {@link ServletServerHttpResponse}.
	 * This can be overridden for custom {@code HttpOutputMessage} implementations
	 * @param servletResponse current HTTP response
	 * @return the HttpInputMessage instance to use
	 * @throws Exception in case of errors
	 */
    protected HttpOutputMessage createHttpOutputMessage(HttpServletResponse servletResponse) throws Exception {
		return new ServletServerHttpResponse(servletResponse);
	}
	
	public class ServletHandlerMethodResolver extends HandlerMethodResolver {
		public ServletHandlerMethodResolver(Class<?> handlerClass) {
			init(handlerClass);
		}
		
		@Override
		protected boolean isHandlerMethod(Method method) {
			return !method.isBridge() && Modifier.isPublic(method.getModifiers()) && !DefaultClassHandlerMapping.ObjectMethods.contains(method.getName());
		}

		public Method resolveHandlerMethod(HttpServletRequest request) throws ServletException {
			Map<RequestMappingInfo, Method> targetHandlerMethods = new LinkedHashMap<RequestMappingInfo, Method>();
			
			String lookupPath = urlPathHelper.getLookupPathForRequest(request);
			Comparator<String> pathComparator = pathMatcher.getPatternComparator(lookupPath);
//			RequestMapping requestMapping = getTypeLevelMapping();
			Set<String> allowedMethods = new LinkedHashSet<String>(7);
			
			for (Method handlerMethod : getHandlerMethods()) {
				RequestMappingInfo mappingInfo = new RequestMappingInfo();
				RequestMapping mapping = AnnotationUtils.findAnnotation(handlerMethod, RequestMapping.class);
				if(mapping!=null){
					mappingInfo.paths = mapping.value();
					if(mappingInfo.paths==null || mappingInfo.paths.length==0)
						mappingInfo.paths = new String[]{getUrl(handlerMethod)};
					
					if (!hasTypeLevelMapping() || !Arrays.equals(mapping.method(), getTypeLevelMapping().method())) {
						mappingInfo.methods = mapping.method();
					}
					if (!hasTypeLevelMapping() || !Arrays.equals(mapping.params(), getTypeLevelMapping().params())) {
						mappingInfo.params = mapping.params();
					}
					if (!hasTypeLevelMapping() || !Arrays.equals(mapping.headers(), getTypeLevelMapping().headers())) {
						mappingInfo.headers = mapping.headers();
					}
				}else{
					mappingInfo.paths = new String[]{getUrl(handlerMethod)};
				}
				
				boolean match = false;
				if (mappingInfo.paths.length > 0) {
					List<String> matchedPaths = new ArrayList<String>(mappingInfo.paths.length);
					for (String mappedPattern : mappingInfo.paths) {
						if (!hasTypeLevelMapping() && !mappedPattern.startsWith("/")) {
							mappedPattern = "/" + mappedPattern;
						}
						String matchedPattern = getMatchedPattern(mappedPattern, lookupPath, request);
						if (matchedPattern != null) {
							if (mappingInfo.matches(request)) {
								match = true;
								matchedPaths.add(matchedPattern);
							}
							else {
								for (RequestMethod requestMethod : mappingInfo.methods) {
									allowedMethods.add(requestMethod.toString());
								}
								break;
							}
						}
					}
					Collections.sort(matchedPaths, pathComparator);
					mappingInfo.matchedPaths = matchedPaths;
				}
				else {
					// No paths specified: parameter match sufficient.
					match = mappingInfo.matches(request);
					for (RequestMethod requestMethod : mappingInfo.methods) {
						allowedMethods.add(requestMethod.toString());
					}
				}
				
				String resolvedMethodName = null;
				if (match) {
					Method oldMappedMethod = targetHandlerMethods.put(mappingInfo, handlerMethod);
					if (oldMappedMethod != null && oldMappedMethod != handlerMethod) {
						if (methodNameResolver != null && mappingInfo.paths.length == 0) {
							if (!oldMappedMethod.getName().equals(handlerMethod.getName())) {
								resolvedMethodName = methodNameResolver.getHandlerMethodName(request);
								if (!resolvedMethodName.equals(oldMappedMethod.getName())) {
									oldMappedMethod = null;
								}
								if (!resolvedMethodName.equals(handlerMethod.getName())) {
									if (oldMappedMethod != null) {
										targetHandlerMethods.put(mappingInfo, oldMappedMethod);
										oldMappedMethod = null;
									}
									else {
										targetHandlerMethods.remove(mappingInfo);
									}
								}
							}
						}
						if (oldMappedMethod != null) {
							throw new IllegalStateException(
									"Ambiguous handler methods mapped for HTTP path '" + lookupPath + "': {" +
											oldMappedMethod + ", " + handlerMethod +
											"}. If you intend to handle the same path in multiple methods, then factor " +
											"them out into a dedicated handler class with that path mapped at the type level!");
						}
					}
				}
			}
			
			if (!targetHandlerMethods.isEmpty()) {
				List<RequestMappingInfo> matches = new ArrayList<RequestMappingInfo>(targetHandlerMethods.keySet());
				RequestMappingInfoComparator requestMappingInfoComparator = new RequestMappingInfoComparator(pathComparator, request);
				Collections.sort(matches, requestMappingInfoComparator);
				RequestMappingInfo bestMappingMatch = matches.get(0);
				String bestMatchedPath = bestMappingMatch.bestMatchedPath();
				if (bestMatchedPath != null) {
					extractHandlerMethodUriTemplates(bestMatchedPath, lookupPath, request);
				}
				return targetHandlerMethods.get(bestMappingMatch);
			}
			else {
				if (!allowedMethods.isEmpty()) {
					throw new HttpRequestMethodNotSupportedException(request.getMethod(),
							StringUtils.toStringArray(allowedMethods));
				}
				else {
					throw new NoSuchRequestHandlingMethodException(lookupPath, request.getMethod(),
							request.getParameterMap());
				}
			}
//			
//			String values[] = requestMapping.value();
//			
//			String url, name;
//			for(String value:values){
//				if(!value.startsWith("/"))
//					value="/"+value;
//				
//				for(Method method : getHandlerMethods()){
//					url = value;
//					name = method.getName();
//					if(!name.startsWith("_")){
//						url+="/"+name;
//					}
//					
//					if(lookupPath.equals(url))
//						return method;
//				}
//			}
//			
//			return null;
		}
		
		private void extractHandlerMethodUriTemplates(String mappedPattern,
				String lookupPath,
				HttpServletRequest request) {

			@SuppressWarnings("unchecked")
			Map<String, String> variables = (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);

			int patternVariableCount = StringUtils.countOccurrencesOf(mappedPattern, "{");
			
			if ( (variables == null || patternVariableCount != variables.size())  
					&& pathMatcher.match(mappedPattern, lookupPath)) {
				variables = pathMatcher.extractUriTemplateVariables(mappedPattern, lookupPath);
				request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, variables);
			}
		}
		
		private boolean isPathMatchInternal(String pattern, String lookupPath) {
			if (pattern.equals(lookupPath) || pathMatcher.match(pattern, lookupPath)) {
				return true;
			}
			boolean hasSuffix = pattern.indexOf('.') != -1;
			if (!hasSuffix && pathMatcher.match(pattern + ".*", lookupPath)) {
				return true;
			}
			boolean endsWithSlash = pattern.endsWith("/");
			if (!endsWithSlash && pathMatcher.match(pattern + "/", lookupPath)) {
				return true;
			}
			return false;
		}
		
		private String getMatchedPattern(String methodLevelPattern, String lookupPath, HttpServletRequest request) {
			if (hasTypeLevelMapping() && (!ObjectUtils.isEmpty(getTypeLevelMapping().value()))) {
				String[] typeLevelPatterns = getTypeLevelMapping().value();
				for (String typeLevelPattern : typeLevelPatterns) {
					if (!typeLevelPattern.startsWith("/")) {
						typeLevelPattern = "/" + typeLevelPattern;
					}
					String combinedPattern = pathMatcher.combine(typeLevelPattern, methodLevelPattern);
					if (isPathMatchInternal(combinedPattern, lookupPath)) {
						return combinedPattern;
					}
				}
				return null;
			}
			String bestMatchingPattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
			if (StringUtils.hasText(bestMatchingPattern) && bestMatchingPattern.endsWith("*")) {
				String combinedPattern = pathMatcher.combine(bestMatchingPattern, methodLevelPattern);
				if (!combinedPattern.equals(bestMatchingPattern) &&
						(isPathMatchInternal(combinedPattern, lookupPath))) {
					return combinedPattern;
				}
			}
			if (isPathMatchInternal(methodLevelPattern, lookupPath)) {
				return methodLevelPattern;
			}
			return null;
		}
		
		public String getUrl(Method method){
			if(method.getName().startsWith("_"))
				return null;
			else
				return "/"+method.getName();
		}
	}

	/**
	 * Servlet-specific subclass of {@link HandlerMethodInvoker}.
	 */
	private class ServletHandlerMethodInvoker extends HandlerMethodInvoker {

		private boolean responseArgumentUsed = false;

		private ServletHandlerMethodInvoker(HandlerMethodResolver resolver) {
			super(resolver, webBindingInitializer, sessionAttributeStore, parameterNameDiscoverer,
					customArgumentResolvers, messageConverters);
		}

		@Override
		protected void raiseMissingParameterException(String paramName, @SuppressWarnings("rawtypes") Class paramType) throws Exception {
			throw new MissingServletRequestParameterException(paramName, paramType.getSimpleName());
		}

		@Override
		protected void raiseSessionRequiredException(String message) throws Exception {
			throw new HttpSessionRequiredException(message);
		}

		@Override
		protected WebDataBinder createBinder(NativeWebRequest webRequest, Object target, String objectName)
				throws Exception {

			return MethodHandlerAdapter.this.createBinder(
					webRequest.getNativeRequest(HttpServletRequest.class), target, objectName);
		}

		@Override
		protected void doBind(WebDataBinder binder, NativeWebRequest webRequest) throws Exception {
			ServletRequestDataBinder servletBinder = (ServletRequestDataBinder) binder;
			servletBinder.bind(webRequest.getNativeRequest(ServletRequest.class));
		}

		@Override
		protected HttpInputMessage createHttpInputMessage(NativeWebRequest webRequest) throws Exception {
			HttpServletRequest servletRequest = webRequest.getNativeRequest(HttpServletRequest.class);
			return MethodHandlerAdapter.this.createHttpInputMessage(servletRequest);
		}

        @Override
		protected HttpOutputMessage createHttpOutputMessage(NativeWebRequest webRequest) throws Exception {
			HttpServletResponse servletResponse = (HttpServletResponse) webRequest.getNativeResponse();
			return MethodHandlerAdapter.this.createHttpOutputMessage(servletResponse);
		}

		@Override
		protected Object resolveDefaultValue(String value) {
			if (beanFactory == null) {
				return value;
			}
			String placeholdersResolved = beanFactory.resolveEmbeddedValue(value);
			BeanExpressionResolver exprResolver = beanFactory.getBeanExpressionResolver();
			if (exprResolver == null) {
				return value;
			}
			return exprResolver.evaluate(placeholdersResolved, expressionContext);
		}

		@Override
		protected Object resolveCookieValue(String cookieName, @SuppressWarnings("rawtypes") Class paramType, NativeWebRequest webRequest)
				throws Exception {

			HttpServletRequest servletRequest = webRequest.getNativeRequest(HttpServletRequest.class);
			Cookie cookieValue = WebUtils.getCookie(servletRequest, cookieName);
			if (Cookie.class.isAssignableFrom(paramType)) {
				return cookieValue;
			}
			else if (cookieValue != null) {
				return cookieValue.getValue();
			}
			else {
				return null;
			}
		}

		@Override
		@SuppressWarnings({"unchecked"})
		protected String resolvePathVariable(String pathVarName, @SuppressWarnings("rawtypes") Class paramType, NativeWebRequest webRequest)
				throws Exception {

			HttpServletRequest servletRequest = webRequest.getNativeRequest(HttpServletRequest.class);
			Map<String, String> uriTemplateVariables =
					(Map<String, String>) servletRequest.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
			if (uriTemplateVariables == null || !uriTemplateVariables.containsKey(pathVarName)) {
				throw new IllegalStateException(
						"Could not find @PathVariable [" + pathVarName + "] in @RequestMapping");
			}
			return uriTemplateVariables.get(pathVarName);
		}

		@Override
		protected Object resolveStandardArgument(@SuppressWarnings("rawtypes") Class parameterType, NativeWebRequest webRequest) throws Exception {
			HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
			HttpServletResponse response = webRequest.getNativeResponse(HttpServletResponse.class);

			if (ServletRequest.class.isAssignableFrom(parameterType)) {
				return request;
			}
			else if (ServletResponse.class.isAssignableFrom(parameterType)) {
				this.responseArgumentUsed = true;
				return response;
			}
			else if (HttpSession.class.isAssignableFrom(parameterType)) {
				return request.getSession();
			}
			else if (Principal.class.isAssignableFrom(parameterType)) {
				return request.getUserPrincipal();
			}
			else if (Locale.class.equals(parameterType)) {
				return RequestContextUtils.getLocale(request);
			}
			else if (InputStream.class.isAssignableFrom(parameterType)) {
				return request.getInputStream();
			}
			else if (Reader.class.isAssignableFrom(parameterType)) {
				return request.getReader();
			}
			else if (OutputStream.class.isAssignableFrom(parameterType)) {
				this.responseArgumentUsed = true;
				return response.getOutputStream();
			}
			else if (Writer.class.isAssignableFrom(parameterType)) {
				this.responseArgumentUsed = true;
				return response.getWriter();
			}
			return super.resolveStandardArgument(parameterType, webRequest);
		}

		@SuppressWarnings({ "unchecked", "rawtypes" })
		public ModelAndView getModelAndView(Method handlerMethod, Class handlerType, Object returnValue,
				ExtendedModelMap implicitModel, ServletWebRequest webRequest) throws Exception {

			ResponseStatus responseStatusAnn = AnnotationUtils.findAnnotation(handlerMethod, ResponseStatus.class);
			if (responseStatusAnn != null) {
				HttpStatus responseStatus = responseStatusAnn.value();
				String reason = responseStatusAnn.reason();
				if (!StringUtils.hasText(reason)) {
					webRequest.getResponse().setStatus(responseStatus.value());
				}
				else {
					webRequest.getResponse().sendError(responseStatus.value(), reason);
				}

				// to be picked up by the RedirectView
				webRequest.getRequest().setAttribute(View.RESPONSE_STATUS_ATTRIBUTE, responseStatus);

				responseArgumentUsed = true;
			}

			// Invoke custom resolvers if present...
			if (customModelAndViewResolvers != null) {
				for (ModelAndViewResolver mavResolver : customModelAndViewResolvers) {
					ModelAndView mav = mavResolver
							.resolveModelAndView(handlerMethod, handlerType, returnValue, implicitModel, webRequest);
					if (mav != ModelAndViewResolver.UNRESOLVED) {
						return mav;
					}
				}
			}

			if (returnValue instanceof HttpEntity) {
				handleHttpEntityResponse((HttpEntity<?>) returnValue, webRequest);
				return null;
			}
			else if (AnnotationUtils.findAnnotation(handlerMethod, ResponseBody.class) != null) {
				handleResponseBody(returnValue, webRequest);
				return null;
			}
			else if (AnnotationUtils.findAnnotation(handlerMethod, Json.class) != null) {
				handleResponseJsonBody(returnValue, webRequest);
				return null;
			}
			else if (returnValue instanceof ModelAndView) {
				ModelAndView mav = (ModelAndView) returnValue;
				mav.getModelMap().mergeAttributes(implicitModel);
				return mav;
			}
			else if (returnValue instanceof Model) {
				return new ModelAndView().addAllObjects(implicitModel).addAllObjects(((Model) returnValue).asMap());
			}
			else if (returnValue instanceof View) {
				return new ModelAndView((View) returnValue).addAllObjects(implicitModel);
			}
			else if (AnnotationUtils.findAnnotation(handlerMethod, ModelAttribute.class) != null) {
				addReturnValueAsModelAttribute(handlerMethod, handlerType, returnValue, implicitModel);
				return new ModelAndView().addAllObjects(implicitModel);
			}
			else if (returnValue instanceof Map) {
				return new ModelAndView().addAllObjects(implicitModel).addAllObjects((Map) returnValue);
			}
			else if (returnValue instanceof String) {
				return new ModelAndView((String) returnValue).addAllObjects(implicitModel);
			}
			else if (returnValue == null) {
				// Either returned null or was 'void' return.
				if (this.responseArgumentUsed || webRequest.isNotModified()) {
					return null;
				}
				else {
					// Assuming view name translation...
					return new ModelAndView().addAllObjects(implicitModel);
				}
			}
			else if (!BeanUtils.isSimpleProperty(returnValue.getClass())) {
				// Assume a single model attribute...
				addReturnValueAsModelAttribute(handlerMethod, handlerType, returnValue, implicitModel);
				return new ModelAndView().addAllObjects(implicitModel);
			}
			else {
				throw new IllegalArgumentException("Invalid handler method return value: " + returnValue);
			}
		}

		private void handleResponseJsonBody(Object value, ServletWebRequest request) throws Exception{
			if (value == null) {
				return;
			}
//			HttpInputMessage inputMessage = createHttpInputMessage(request);
			HttpOutputMessage outputMessage = createHttpOutputMessage(request);
			//outputMessage.getHeaders().add("Context-Type", "text/javascript; charset=UTF-8");
			outputMessage.getHeaders().add("Context-Type", "application/json; charset=UTF-8");
			outputMessage.getBody().write(JSONObject.fromObject(value).toString().getBytes("UTF-8"));
			outputMessage.getBody().flush();
		}
		
		private void handleResponseBody(Object returnValue, ServletWebRequest webRequest)
				throws Exception {
			if (returnValue == null) {
				return;
			}
			HttpInputMessage inputMessage = createHttpInputMessage(webRequest);
			HttpOutputMessage outputMessage = createHttpOutputMessage(webRequest);
			writeWithMessageConverters(returnValue, inputMessage, outputMessage);
		}

		@SuppressWarnings("rawtypes")
		private void handleHttpEntityResponse(HttpEntity<?> responseEntity, ServletWebRequest webRequest)
				throws Exception {
			if (responseEntity == null) {
				return;
			}
			HttpInputMessage inputMessage = createHttpInputMessage(webRequest);
			HttpOutputMessage outputMessage = createHttpOutputMessage(webRequest);
			if (responseEntity instanceof ResponseEntity && outputMessage instanceof ServerHttpResponse) {
				((ServerHttpResponse)outputMessage).setStatusCode(((ResponseEntity) responseEntity).getStatusCode());
			}
			HttpHeaders entityHeaders = responseEntity.getHeaders();
			if (!entityHeaders.isEmpty()) {
				outputMessage.getHeaders().putAll(entityHeaders);
			}
			Object body = responseEntity.getBody();
			if (body != null) {
				writeWithMessageConverters(body, inputMessage, outputMessage);
			}
		}

		@SuppressWarnings("unchecked")
		private void writeWithMessageConverters(Object returnValue,
				HttpInputMessage inputMessage, HttpOutputMessage outputMessage)
				throws IOException, HttpMediaTypeNotAcceptableException {
			List<MediaType> acceptedMediaTypes = inputMessage.getHeaders().getAccept();
			if (acceptedMediaTypes.isEmpty()) {
				acceptedMediaTypes = Collections.singletonList(MediaType.ALL);
			}
			MediaType.sortByQualityValue(acceptedMediaTypes);
			Class<?> returnValueType = returnValue.getClass();
			List<MediaType> allSupportedMediaTypes = new ArrayList<MediaType>();
			if (getMessageConverters() != null) {
				for (MediaType acceptedMediaType : acceptedMediaTypes) {
					for (@SuppressWarnings("rawtypes") HttpMessageConverter messageConverter : getMessageConverters()) {
						if (messageConverter.canWrite(returnValueType, acceptedMediaType)) {
							messageConverter.write(returnValue, acceptedMediaType, outputMessage);
							if (logger.isDebugEnabled()) {
								MediaType contentType = outputMessage.getHeaders().getContentType();
								if (contentType == null) {
									contentType = acceptedMediaType;
								}
								logger.debug("Written [" + returnValue + "] as \"" + contentType +
										"\" using [" + messageConverter + "]");
							}
							this.responseArgumentUsed = true;
							return;
						}
					}
				}
				for (@SuppressWarnings("rawtypes") HttpMessageConverter messageConverter : messageConverters) {
					allSupportedMediaTypes.addAll(messageConverter.getSupportedMediaTypes());
				}
			}
			throw new HttpMediaTypeNotAcceptableException(allSupportedMediaTypes);
		}

	}
	
	/**
	 * Return the message body converters that this adapter has been configured with.
	 */
	public HttpMessageConverter<?>[] getMessageConverters() {
		return messageConverters;
	}

	public ConfigurableBeanFactory getBeanFactory() {
		return beanFactory;
	}

	public BeanExpressionContext getExpressionContext() {
		return expressionContext;
	}

	public void setExpressionContext(BeanExpressionContext expressionContext) {
		this.expressionContext = expressionContext;
	}

	public int getCacheSecondsForSessionAttributeHandlers() {
		return cacheSecondsForSessionAttributeHandlers;
	}

	public void setCacheSecondsForSessionAttributeHandlers(int cacheSecondsForSessionAttributeHandlers) {
		this.cacheSecondsForSessionAttributeHandlers = cacheSecondsForSessionAttributeHandlers;
	}

	public boolean isSynchronizeOnSession() {
		return synchronizeOnSession;
	}

	public void setSynchronizeOnSession(boolean synchronizeOnSession) {
		this.synchronizeOnSession = synchronizeOnSession;
	}

	public UrlPathHelper getUrlPathHelper() {
		return urlPathHelper;
	}

	public void setUrlPathHelper(UrlPathHelper urlPathHelper) {
		this.urlPathHelper = urlPathHelper;
	}

	public WebBindingInitializer getWebBindingInitializer() {
		return webBindingInitializer;
	}

	public void setWebBindingInitializer(WebBindingInitializer webBindingInitializer) {
		this.webBindingInitializer = webBindingInitializer;
	}

	public SessionAttributeStore getSessionAttributeStore() {
		return sessionAttributeStore;
	}

	public void setSessionAttributeStore(SessionAttributeStore sessionAttributeStore) {
		this.sessionAttributeStore = sessionAttributeStore;
	}

	public ParameterNameDiscoverer getParameterNameDiscoverer() {
		return parameterNameDiscoverer;
	}

	public void setParameterNameDiscoverer(ParameterNameDiscoverer parameterNameDiscoverer) {
		this.parameterNameDiscoverer = parameterNameDiscoverer;
	}

	public WebArgumentResolver[] getCustomArgumentResolvers() {
		return customArgumentResolvers;
	}

	public void setCustomArgumentResolvers(WebArgumentResolver[] customArgumentResolvers) {
		this.customArgumentResolvers = customArgumentResolvers;
	}

	public ModelAndViewResolver[] getCustomModelAndViewResolvers() {
		return customModelAndViewResolvers;
	}

	public void setCustomModelAndViewResolvers(ModelAndViewResolver[] customModelAndViewResolvers) {
		this.customModelAndViewResolvers = customModelAndViewResolvers;
	}

	public Map<Class<?>, ServletHandlerMethodResolver> getMethodResolverCache() {
		return methodResolverCache;
	}

	public void setOrder(int order) {
		this.order = order;
	}

	public void setMessageConverters(HttpMessageConverter<?>[] messageConverters) {
		this.messageConverters = messageConverters;
	}
}
