package com.jvmer.frame.web.servlet.mvc;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;

public class RequestMappingInfoComparator implements Comparator<RequestMappingInfo> {

	private final Comparator<String> pathComparator;

	private final ServerHttpRequest request;

	RequestMappingInfoComparator(Comparator<String> pathComparator, HttpServletRequest request) {
		this.pathComparator = pathComparator;
		this.request = new ServletServerHttpRequest(request);
	}

	public int compare(RequestMappingInfo info1, RequestMappingInfo info2) {
		int pathComparison = pathComparator.compare(info1.bestMatchedPath(), info2.bestMatchedPath());
		if (pathComparison != 0) {
			return pathComparison;
		}
		int info1ParamCount = info1.params.length;
		int info2ParamCount = info2.params.length;
		if (info1ParamCount != info2ParamCount) {
			return info2ParamCount - info1ParamCount;
		}
		int info1HeaderCount = info1.headers.length;
		int info2HeaderCount = info2.headers.length;
		if (info1HeaderCount != info2HeaderCount) {
			return info2HeaderCount - info1HeaderCount;
		}
		int acceptComparison = compareAcceptHeaders(info1, info2);
		if (acceptComparison != 0) {
			return acceptComparison;
		}
		int info1MethodCount = info1.methods.length;
		int info2MethodCount = info2.methods.length;
		if (info1MethodCount == 0 && info2MethodCount > 0) {
			return 1;
		}
		else if (info2MethodCount == 0 && info1MethodCount > 0) {
			return -1;
		}
		else if (info1MethodCount == 1 & info2MethodCount > 1) {
			return -1;
		}
		else if (info2MethodCount == 1 & info1MethodCount > 1) {
			return 1;
		}
		return 0;
	}

	private int compareAcceptHeaders(RequestMappingInfo info1, RequestMappingInfo info2) {
		List<MediaType> requestAccepts = request.getHeaders().getAccept();
		MediaType.sortByQualityValue(requestAccepts);
		
		List<MediaType> info1Accepts = getAcceptHeaderValue(info1);
		List<MediaType> info2Accepts = getAcceptHeaderValue(info2);

		for (MediaType requestAccept : requestAccepts) {
			int pos1 = indexOfIncluded(info1Accepts, requestAccept);
			int pos2 = indexOfIncluded(info2Accepts, requestAccept);
			if (pos1 != pos2) {
				return pos2 - pos1;
			}
		}
		return 0;
	}

	private int indexOfIncluded(List<MediaType> infoAccepts, MediaType requestAccept) {
		for (int i = 0; i < infoAccepts.size(); i++) {
			MediaType info1Accept = infoAccepts.get(i);
			if (requestAccept.includes(info1Accept)) {
				return i;
			}
		}
		return -1;
	}

	private List<MediaType> getAcceptHeaderValue(RequestMappingInfo info) {
		for (String header : info.headers) {
			int separator = header.indexOf('=');
			if (separator != -1) {
				String key = header.substring(0, separator);
				String value = header.substring(separator + 1);
				if ("Accept".equalsIgnoreCase(key)) {
					return MediaType.parseMediaTypes(value);
				}
			}
		}
		return Collections.emptyList();
	}
}
