package com.djstudio.ws;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Part of /actuator/health: the real-time gateway is up and how busy it is (PRD 10.3). */
@Component("gateway")
class GatewayHealthIndicator implements HealthIndicator {

    private final RoomService rooms;

    GatewayHealthIndicator(RoomService rooms) {
        this.rooms = rooms;
    }

    @Override
    public Health health() {
        return Health.up().withDetail("liveRooms", rooms.liveRooms()).withDetail("openConnections", rooms.openConnections())
                .build();
    }
}
