package demo;

public class GamePanel {
    private int x = 1;
    private int y = 2;

    public void tick() {
        movePoint();
        System.out.println(x + y);
    }

    private void movePoint() {
        x = x + 1;
        y = y + 1;
    }
}
