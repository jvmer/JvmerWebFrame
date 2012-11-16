package com.jvmer.frame.web.servlet.mvc;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.RequestMethod;

class RequestMappingInfo {

	String[] paths = new String[0];

	List<String> matchedPaths = Collections.emptyList();

	RequestMethod[] methods = new RequestMethod[0];

	String[] params = new String[0];

	String[] headers = new String[0];

	public String bestMatchedPath() {
		return (!this.matchedPaths.isEmpty() ? this.matchedPaths.get(0) : null);
	}

	public boolean matches(HttpServletRequest request) {
		return ServletAnnotationMappingUtils.checkRequestMethod(this.methods, request) &&
				ServletAnnotationMappingUtils.checkParameters(this.params, request) &&
				ServletAnnotationMappingUtils.checkHeaders(this.headers, request);
	}

	@Override
	public boolean equals(Object obj) {
		RequestMappingInfo other = (RequestMappingInfo) obj;
		return (Arrays.equals(this.paths, other.paths) && Arrays.equals(this.methods, other.methods) &&
				Arrays.equals(this.params, other.params) && Arrays.equals(this.headers, other.headers));
	}

	@Override
	public int hashCode() {
		return (Arrays.hashCode(this.paths) * 23 + Arrays.hashCode(this.methods) * 29 +
				Arrays.hashCode(this.params) * 31 + Arrays.hashCode(this.headers));
	}
}
