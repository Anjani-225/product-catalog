package com.example.productcatalog.exception;

public class StaleProductVersionException extends RuntimeException {
    public StaleProductVersionException(long id, long supplied, long current) {
        super("Product " + id + " has version " + current + "; supplied version was " + supplied);
    }
}
