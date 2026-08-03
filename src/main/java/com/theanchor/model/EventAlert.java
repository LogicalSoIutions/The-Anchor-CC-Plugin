package com.theanchor.model;

/** The JSON shape published by the event-alert repository. */
public final class EventAlert
{
	private String message;
	private String time;

	public EventAlert() {}

	public EventAlert(String message, String time)
	{
		this.message = message;
		this.time = time;
	}

	public String getMessage() { return message; }
	public String getTime() { return time; }
}
