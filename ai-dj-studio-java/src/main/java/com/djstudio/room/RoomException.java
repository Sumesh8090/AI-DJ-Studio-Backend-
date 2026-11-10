package com.djstudio.room;

/** A rule violation inside a room (forbidden, frozen, duplicate...). The gateway turns it into a lounge.error event. */
public class RoomException extends RuntimeException {

    private final String code;

    public RoomException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
