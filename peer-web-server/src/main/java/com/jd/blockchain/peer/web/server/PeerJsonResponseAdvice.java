package com.jd.blockchain.peer.web.server;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import com.jd.httpservice.utils.web.JsonWebResponseMessageConverter;
import com.jd.httpservice.utils.web.WebResponse;

import utils.serialize.json.JSONSerializeUtils;

@RestControllerAdvice
public class PeerJsonResponseAdvice implements ResponseBodyAdvice<Object> {

	@Override
	public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
		if (StringHttpMessageConverter.class == converterType && returnType.getDeclaringClass().getName().startsWith("com.jd")) {
			return true;
		}
		if (JsonWebResponseMessageConverter.class == converterType && returnType.getDeclaringClass().getName().startsWith("com.jd")) {
			return true;
		}
		if (JsonWebResponseMessageConverter.class == converterType && returnType.getDeclaringClass().getName().startsWith("com.jd")) {
			return true;
		}
		if (MappingJackson2HttpMessageConverter.class == converterType
				&& returnType.getDeclaringClass().getName().startsWith("com.jd")) {
			return true;
		}
		return false;
	}

	@Override
	public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
			Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request,
			ServerHttpResponse response) {
		WebResponse result = null;
		if (body == null) {
			result = WebResponse.createSuccessResult(null);
		} else if (body instanceof ResponseEntity) {
			return body;
		} else if (body instanceof WebResponse) {
			// 把返回结果自动转换为 WebResponse；
			return body;
		}
		if (result == null) {
			result = WebResponse.createSuccessResult(body);
		}
		if (String.class == returnType.getMethod().getReturnType()) {
			return JSONSerializeUtils.serializeToJSON(result);
		}
		return result;
	}

}