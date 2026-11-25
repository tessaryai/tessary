// SPDX-License-Identifier: Apache-2.0
package ai.tessary.endusers.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** The class of a route (spec 1). The codes equal the {@code end_user_route.route_class} check constraint. */
public enum RouteClass implements WireCode {
    A("A"),
    A2("A2"),
    S("S"),
    M("M"),
    B("B"),
    C("C");

    private final String wire;

    RouteClass(String wire) {
        this.wire = wire;
    }

    @JsonValue
    @Override
    public String wire() {
        return wire;
    }

    @JsonCreator
    public static RouteClass fromWire(String value) {
        return WireCode.parse(RouteClass.class, value);
    }
}
