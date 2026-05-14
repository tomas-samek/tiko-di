package io.tiko.examples.http;

/** DTO record parsed from the POST /tickets JSON body. */
public record CreateTicketRequest(String title) {}
