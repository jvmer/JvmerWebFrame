package com.jvmer.frame.web.test.action;
import java.io.IOException;
import java.lang.reflect.Method;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;

import com.jvmer.frame.web.servlet.mvc.Json;

@Controller
@RequestMapping(value="/test/{name2}")
public class Test {

	/**
	 * @param args
	 * @throws IOException 
	 */
	public static void main2(@RequestParam("tests") String[] args, HttpServletResponse response) throws IOException {
		Method[] ms = Test.class.getDeclaredMethods();
		for(Method m:ms){
			System.out.println(m.getName());
		}
		for(String arg:args){
			response.getWriter().println(arg);
		}
	}
	
	/**
	 * @param args
	 * @throws IOException 
	 */
	public void main(@PathVariable("name2") String name2, @RequestParam("name") String name, HttpServletResponse response) throws IOException {
		response.getWriter().println(name2+":"+name);
	}
	
	public String toString() {
		return "test";
	};
	
	public void _index(UserForm form, @RequestParam("id") int id, @RequestParam("rt") float rt, HttpServletRequest request, HttpServletResponse response) throws IOException{
		response.getWriter().println(form);
		response.getWriter().println(request.getRequestURI()+", "+id+", "+rt);
	}
	
	public String test(){
		return "test";
	}
	
	@Json
	@RequestMapping(method=RequestMethod.POST)
	public String json(){
		return "{data:'这个是JSON数据\u2029'}";
	}
	
	@Json
	public UserForm obj(){
		UserForm user = new UserForm();
		user.setName("张波");
		user.setPwd("123");
		user.setTime(System.currentTimeMillis());
		return user;
	}
}
