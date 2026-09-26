package com.cabaccess.shared;

public class Failure extends RuntimeException {
    public final int status;
    public final String code;

    public Failure(int status, String code) {
        super(code);
        this.status = status;
        this.code = code;
    }

    public static void require(boolean ok, int status, String code) {
        if (!ok) throw new Failure(status, code);
    }
}
