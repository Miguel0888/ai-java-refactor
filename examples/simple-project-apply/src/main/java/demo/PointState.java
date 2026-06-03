package demo;

final class PointState {

    private int x = 1;

    private int y = 2;

    void movePoint() {
        x = x + 1;
        y = y + 1;
    }

    int getX() {
        return x;
    }

    int getY() {
        return y;
    }
}
