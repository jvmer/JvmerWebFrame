package com.jvmer.frame.web.servlet.mvc;

import java.lang.reflect.Method;

import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.handler.AbstractDetectingUrlHandlerMapping;

/**
 * 根据注解的类中的方法生成URL.<br/>
 * 生成的方法必须是public修饰, 且不能是Object的方法名, 而且方法不能是bridge, 方法名不能以下划线开始,
 * 方法名区分大小写
 * URL生成
 * @author jvmer
 */
public class DefaultClassHandlerMapping extends AbstractDetectingUrlHandlerMapping {
	/**
	 * 缓存类的RequestMapping注释信息
	 */
	protected final Map<Class<?>, RequestMapping> cachedMappings = new HashMap<Class<?>, RequestMapping>();
	/**
	 * Object类的方法
	 */
	protected final static Set<String> ObjectMethods = new HashSet<>(Arrays.asList("clone", "equals", "finalize", "getClass", "hashCode", "notify", "notifyAll", "toString", "wait"));
	/**
	 * 是否使用后缀配置
	 */
	private boolean useDefaultSuffixPattern = true;
	
	/**
	 * 是否使用后缀配置
	 * @return
	 */
	public boolean isUseDefaultSuffixPattern() {
		return useDefaultSuffixPattern;
	}

	/**
	 * 设置是否使用后缀配置
	 * @param useDefaultSuffixPattern
	 */
	public void setUseDefaultSuffixPattern(boolean useDefaultSuffixPattern) {
		this.useDefaultSuffixPattern = useDefaultSuffixPattern;
	}

	@Override
	protected String[] determineUrlsForHandler(String beanName) {
		ApplicationContext context = getApplicationContext();
		//获取此beanName的是否存在RequestMapping注释
		RequestMapping mapping = context.findAnnotationOnBean(beanName, RequestMapping.class);
		// 只接受RequestMapping在Type一级的注解
		if (mapping != null) {
			Class<?> handlerType = context.getType(beanName);
			// @RequestMapping found at type level
			this.cachedMappings.put(handlerType, mapping);

			//此bean定义的URL
			Set<String> urls = new LinkedHashSet<String>();
			
			//class上注释的URL
			String[] typeLevelPatterns = mapping.value();
			
			//在类上映射了多个路径
			if (typeLevelPatterns.length > 0) {
				String[] methodLevelPatterns = determineUrlsForHandlerMethods(handlerType);
				for (String typeLevelPattern : typeLevelPatterns) {
					if (!typeLevelPattern.startsWith("/")) {
						typeLevelPattern = "/" + typeLevelPattern;
					}
					boolean hasEmptyMethodLevelMappings = false;
					for (String methodLevelPattern : methodLevelPatterns) {
						if (methodLevelPattern == null) {
							hasEmptyMethodLevelMappings = true;
						} else {
							if(typeLevelPattern.endsWith("/"))
								typeLevelPattern = typeLevelPattern.substring(1);
							
							String combinedPattern = getPathMatcher().combine(typeLevelPattern, methodLevelPattern);
							addUrlsForPath(urls, combinedPattern);
						}
					}
					if (hasEmptyMethodLevelMappings || org.springframework.web.servlet.mvc.Controller.class.isAssignableFrom(handlerType)) {
						addUrlsForPath(urls, typeLevelPattern);
					}
				}
				return StringUtils.toStringArray(urls);
			} else {
				//如果class上没有注解,则查找方法上注解的
				return determineUrlsForHandlerMethods(handlerType);
			}
		}
		return null;
	}
	
	/**
	 * Derive URL mappings from the handler's method-level mappings.
	 * @param handlerType the handler type to introspect
	 * within a type-level mapping
	 * @return the array of mapped URLs
	 */
	protected String[] determineUrlsForHandlerMethods(Class<?> handlerType) {
		final Set<String> urls = new LinkedHashSet<String>();
		Class<?>[] handlerTypes = Proxy.isProxyClass(handlerType) ? handlerType.getInterfaces() : new Class<?>[]{handlerType};
		for (Class<?> currentHandlerType : handlerTypes) {
			Method[] methods = currentHandlerType.getDeclaredMethods();
			for(Method method:methods){
				if(!method.isBridge() && Modifier.isPublic(method.getModifiers()) && !ObjectMethods.contains(method.getName())){
					RequestMapping mapping = AnnotationUtils.findAnnotation(method, RequestMapping.class);
					if(mapping!=null){
						if(mapping.value()!=null && mapping.value().length>0){
							for(String path:mapping.value())
								addUrlsForPath(urls, path);
						}else{
							addUrlsForPath(urls, method.getName());
						}
					} else if(method.getName().startsWith("_"))
						urls.add(null);
					else
						addUrlsForPath(urls, method.getName());
				}
			}
		}
		return StringUtils.toStringArray(urls);
	}
	
	protected void addUrlsForPath(Set<String> urls, String path) {
		if(!path.startsWith("/"))
			path = "/"+path;
		urls.add(path);
		if (this.useDefaultSuffixPattern && path.indexOf('.') == -1 && !path.endsWith("/")) {
			urls.add(path + ".*");
			urls.add(path + "/");
		}
	}
}
