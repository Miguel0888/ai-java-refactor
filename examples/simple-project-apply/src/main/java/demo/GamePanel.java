package demo;

public class GamePanel {
        private final PointState pointState = new PointState();

public void tick() {
        pointState.movePoint();
        System.out.println(pointState.getX() + pointState.getY());
    }

    }
