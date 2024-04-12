package de.gmasil.webproject.videohandler.exception;

import lombok.Getter;

@Getter
public class ExecutionException extends RuntimeException {

    private final String output;

    public ExecutionException(String output) {
        this.output = output;
    }
}
