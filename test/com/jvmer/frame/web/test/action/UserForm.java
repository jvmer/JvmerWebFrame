package com.jvmer.frame.web.test.action;

public class UserForm {
	private String name;
	private String pwd;
	private long time;
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public String getPwd() {
		return pwd;
	}
	public void setPwd(String pwd) {
		this.pwd = pwd;
	}
	public long getTime() {
		return time;
	}
	public void setTime(long time) {
		this.time = time;
	}
	@Override
	public String toString() {
		return "name:"+name+"<br/>pwd:"+pwd+"<br/>time:"+time+"<br/>";
	}
}
