package com.djstudio.ws;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** One-second heartbeat: presence expiry, track end, outro-window AI trigger, auto-approve, queue persistence. */
@Component
class RoomTicker {

    private final RoomService rooms;

    RoomTicker(RoomService rooms) {
        this.rooms = rooms;
    }

    @Scheduled(fixedRate = 1000)
    void tick() {
        rooms.tick();
    }
}
