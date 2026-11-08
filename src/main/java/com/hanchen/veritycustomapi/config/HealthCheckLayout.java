package com.hanchen.veritycustomapi.config;

/** Shared geometry for drawing and hit testing, independent of the game runtime. */
public record HealthCheckLayout(int left, int top, int right, int bottom) {
    public static HealthCheckLayout of(int width, int height, boolean title) {
        int mx = title ? 0 : Math.min(16, Math.max(0, (width - 280) / 2));
        int my = title ? 0 : Math.min(12, Math.max(0, (height - 220) / 2));
        int w = title ? width : Math.min(600, Math.max(0, width - mx * 2));
        int h = title ? height : Math.min(350, Math.max(0, height - my * 2));
        int x = (width - w) / 2, y = (height - h) / 2;
        return new HealthCheckLayout(x, y, x + w, y + h);
    }
    public int innerWidth() { return Math.max(0, right - left - 20); }
    public int rowTop() { return top + 50; }
    public int rowHeight() { return bottom - top < 250 ? 23 : 29; }
    public int detailTop() { return rowTop() + rowHeight() * 3 + 6; }
    public int detailBottom() { return Math.max(detailTop(), bottom - 35); }
    public int footerY() { return bottom - 26; }
}
