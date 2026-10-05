package com.slavaslava.transferapi.web.ui;

/** An error shown on the terminal page: a short title and the detail, like an RFC 7807 problem. */
public record UiMessage(String title, String detail) {
}
