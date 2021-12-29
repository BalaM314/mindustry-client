package mindustry.client.navigation;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.geom.*;
import arc.struct.*;
import mindustry.client.navigation.waypoints.*;
import mindustry.graphics.*;

/** A {@link Path} composed of {@link Waypoint} instances. */
public class WaypointPath<T extends Waypoint> extends Path {
    public Seq<T> waypoints = new Seq<>();
    private Seq<T> initial;
    private int initialSize;
    private boolean show;

    public WaypointPath(Seq<T> waypoints) {
        this.waypoints = waypoints;
        this.initial = waypoints.copy();
        this.initialSize = waypoints.size;
    }

    @SafeVarargs
    public WaypointPath(T... waypoints) {
        this.waypoints.clear();
        this.waypoints.addAll(waypoints);
        this.initial = Seq.with(waypoints);
        this.initialSize = waypoints.length;
    }

    public synchronized WaypointPath<T> set(Seq<T> waypoints) {
        this.waypoints.set(waypoints);
        if (repeat) initial.set(waypoints); // Don't bother if we aren't repeating
        initialSize = waypoints.size;
        return this;
    }

    public synchronized WaypointPath<T> set(T[] waypoints) {
        this.waypoints.set(waypoints);
        if (repeat) initial.set(waypoints); // Don't bother if we aren't repeating
        initialSize = waypoints.length;
        return this;
    }

    public synchronized WaypointPath<T> add(T waypoint) {
        waypoints.add(waypoint);
        initial.add(waypoint);
        initialSize++;
        return this;
    }

    public synchronized WaypointPath<T> clear() {
        waypoints.clear();
        initial.clear();
        initialSize = 0;
        return this;
    }

    @Override
    public void setShow(boolean show) {
        this.show = show;
    }

    @Override
    public boolean getShow() {
        return show;
    }

    @Override
    public synchronized void follow() {
        if (waypoints == null || waypoints.isEmpty()) return;

        synchronized (this) {
            while (waypoints.size > 1 && Core.settings.getBool("assumeunstrict")) waypoints.remove(0); // Only the last waypoint is needed when we are just teleporting there anyways.
            while (waypoints.any() && waypoints.first().isDone()) {
                waypoints.first().onFinish();
                waypoints.remove(0);
            }
            if (waypoints.any()) waypoints.first().run();
        }
    }

    @Override
    public float progress() {
        if (waypoints == null || initialSize == 0) return 1f;

        return waypoints.size / (float)initialSize;
    }

    @Override
    public synchronized boolean isDone() {
        if (waypoints == null) return true;

        if (waypoints.isEmpty() && repeat) onFinish();
        return waypoints.isEmpty();
    }

    @Override
    public void reset() {
        waypoints.clear();
        waypoints.addAll(initial);
    }

    @Override
    public synchronized void draw() {
        if (show) {
            Position lastWaypoint = null;
            Draw.z(Layer.space);
            for (var waypoint : waypoints) {
                if (waypoint instanceof Position wp) {
                    if (lastWaypoint != null) {
                        Draw.color(Color.blue, 0.4f);
                        Lines.stroke(3f);
                        Lines.line(lastWaypoint.getX(), lastWaypoint.getY(), wp.getX(), wp.getY());
                    }
                    lastWaypoint = wp;
                }
                waypoint.draw();
                Draw.color();
            }
            Draw.color();
        }
    }

    @Override
    public Position next() {
        return waypoints.first() instanceof Position ? (Position)waypoints.first() : null;
    }
}
