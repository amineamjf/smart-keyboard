package com.smartkeyboard.releve;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Icone lineaire dessinee en code (grille 24x24), sans image ni bibliotheque. */
public final class IconView extends View {

    public static final int DOWN = 0, FOLDER = 1, FILE = 2, LIST = 3, SHARE = 4, SAVE = 5, GEAR = 6,
            PHONE = 7, MAIL = 8, SIGMA = 9, RESTORE = 10, TABLE = 11, PLAY = 12, HOME = 13, MIC = 14,
            CAMERA = 15, PIN = 16, CHART = 17, TEXT = 18, KB = 19, BOLT = 20, UP = 21, DN = 22, CHECK = 23;

    private final int type;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF();

    public IconView(Context c, int type, int color) {
        super(c);
        this.type = type;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
    }

    private void line(Canvas c, float x1, float y1, float x2, float y2) {
        c.drawLine(x1, y1, x2, y2, p);
    }

    private void rr(Canvas c, float l, float t, float r, float b, float rad) {
        rf.set(l, t, r, b);
        c.drawRoundRect(rf, rad, rad, p);
    }

    public void setColor(int col) {
        p.setColor(col);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float s = Math.min(getWidth(), getHeight()) / 24f;
        c.save();
        c.translate((getWidth() - 24 * s) / 2f, (getHeight() - 24 * s) / 2f);
        c.scale(s, s);
        p.setStrokeWidth(1.8f);
        p.setStyle(Paint.Style.STROKE);
        switch (type) {
            case DOWN:
                line(c, 12, 4, 12, 15);
                line(c, 7, 10, 12, 15);
                line(c, 17, 10, 12, 15);
                line(c, 5, 19.5f, 19, 19.5f);
                break;
            case FOLDER:
                path.reset();
                path.moveTo(3, 8);
                path.quadTo(3, 5.5f, 5.5f, 5.5f);
                path.lineTo(9.5f, 5.5f);
                path.lineTo(11.5f, 8);
                path.lineTo(18.5f, 8);
                path.quadTo(21, 8, 21, 10.5f);
                path.lineTo(21, 17);
                path.quadTo(21, 19.5f, 18.5f, 19.5f);
                path.lineTo(5.5f, 19.5f);
                path.quadTo(3, 19.5f, 3, 17);
                path.close();
                c.drawPath(path, p);
                break;
            case FILE:
            case TEXT:
                path.reset();
                path.moveTo(14, 3);
                path.lineTo(7, 3);
                path.quadTo(5, 3, 5, 5);
                path.lineTo(5, 19);
                path.quadTo(5, 21, 7, 21);
                path.lineTo(17, 21);
                path.quadTo(19, 21, 19, 19);
                path.lineTo(19, 8);
                path.close();
                c.drawPath(path, p);
                line(c, 14, 3, 14, 8);
                line(c, 14, 8, 19, 8);
                if (type == TEXT) {
                    line(c, 8.5f, 13, 15.5f, 13);
                    line(c, 8.5f, 16.5f, 15.5f, 16.5f);
                } else {
                    line(c, 9, 13, 13, 17);
                    line(c, 13, 13, 9, 17);
                }
                break;
            case LIST:
                line(c, 9, 6, 20, 6);
                line(c, 9, 12, 20, 12);
                line(c, 9, 18, 20, 18);
                line(c, 4, 6, 5.5f, 7.5f);
                line(c, 5.5f, 7.5f, 7, 4.8f);
                line(c, 4, 12, 5.5f, 13.5f);
                line(c, 5.5f, 13.5f, 7, 10.8f);
                line(c, 4, 18, 5.5f, 19.5f);
                line(c, 5.5f, 19.5f, 7, 16.8f);
                break;
            case SHARE:
                c.drawCircle(6, 12, 2.6f, p);
                c.drawCircle(18, 6, 2.6f, p);
                c.drawCircle(18, 18, 2.6f, p);
                line(c, 8.3f, 10.8f, 15.7f, 7.2f);
                line(c, 8.3f, 13.2f, 15.7f, 16.8f);
                break;
            case SAVE:
                rr(c, 4, 3.5f, 20, 20.5f, 3);
                line(c, 8, 3.5f, 8, 8.5f);
                line(c, 8, 8.5f, 15, 8.5f);
                line(c, 15, 8.5f, 15, 3.5f);
                rr(c, 7.5f, 13, 16.5f, 20.5f, 1.5f);
                break;
            case GEAR:
                c.drawCircle(12, 12, 3.2f, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    float cx = (float) Math.cos(a), cy = (float) Math.sin(a);
                    line(c, 12 + cx * 6.2f, 12 + cy * 6.2f, 12 + cx * 9, 12 + cy * 9);
                }
                c.drawCircle(12, 12, 6.2f, p);
                break;
            case PHONE:
                rr(c, 7, 2.5f, 17, 21.5f, 3);
                line(c, 10.5f, 18.5f, 13.5f, 18.5f);
                break;
            case MAIL:
                rr(c, 3, 5.5f, 21, 18.5f, 3);
                line(c, 3.8f, 7, 12, 13);
                line(c, 12, 13, 20.2f, 7);
                break;
            case SIGMA:
                path.reset();
                path.moveTo(18, 5);
                path.lineTo(6, 5);
                path.lineTo(12, 12);
                path.lineTo(6, 19);
                path.lineTo(18, 19);
                c.drawPath(path, p);
                break;
            case RESTORE:
                rf.set(4.5f, 4.5f, 19.5f, 19.5f);
                c.drawArc(rf, -60, 300, false, p);
                line(c, 17.8f, 3.2f, 17.4f, 8f);
                line(c, 17.4f, 8f, 12.8f, 7.2f);
                break;
            case TABLE:
                rr(c, 3, 4, 21, 20, 3);
                line(c, 3, 10, 21, 10);
                line(c, 9.5f, 4, 9.5f, 20);
                break;
            case PLAY:
                path.reset();
                path.moveTo(8, 5);
                path.lineTo(19, 12);
                path.lineTo(8, 19);
                path.close();
                c.drawPath(path, p);
                break;
            case CHECK:
                p.setStrokeWidth(2.8f);
                line(c, 5, 12.5f, 9.5f, 17);
                line(c, 9.5f, 17, 19, 7);
                break;
            case HOME:
                path.reset();
                path.moveTo(3.5f, 11);
                path.lineTo(12, 3.5f);
                path.lineTo(20.5f, 11);
                path.lineTo(20.5f, 20);
                path.lineTo(14.5f, 20);
                path.lineTo(14.5f, 14);
                path.lineTo(9.5f, 14);
                path.lineTo(9.5f, 20);
                path.lineTo(3.5f, 20);
                path.close();
                c.drawPath(path, p);
                break;
            case MIC:
                rr(c, 9, 3, 15, 14, 3);
                rf.set(5.5f, 7, 18.5f, 18);
                c.drawArc(rf, 0, 180, false, p);
                line(c, 12, 18, 12, 21);
                break;
            case CAMERA:
                rr(c, 3, 7.5f, 21, 19.5f, 3);
                c.drawCircle(12, 13.5f, 3.4f, p);
                line(c, 8, 7.5f, 9.5f, 4.5f);
                line(c, 9.5f, 4.5f, 14.5f, 4.5f);
                line(c, 14.5f, 4.5f, 16, 7.5f);
                break;
            case PIN:
                c.drawCircle(12, 10, 3, p);
                path.reset();
                path.moveTo(12, 21);
                path.cubicTo(4, 14, 5, 3.5f, 12, 3.5f);
                path.cubicTo(19, 3.5f, 20, 14, 12, 21);
                c.drawPath(path, p);
                break;
            case CHART:
                line(c, 5, 20, 5, 4);
                line(c, 5, 20, 20, 20);
                rr(c, 8, 12, 11, 20, 1);
                rr(c, 13, 7, 16, 20, 1);
                break;
            case KB:
                rr(c, 2.5f, 6, 21.5f, 18, 3);
                line(c, 6.5f, 10, 6.6f, 10);
                line(c, 10.5f, 10, 10.6f, 10);
                line(c, 14.5f, 10, 14.6f, 10);
                line(c, 18, 10, 18.1f, 10);
                line(c, 7.5f, 14.5f, 16.5f, 14.5f);
                break;
            case BOLT:
                path.reset();
                path.moveTo(13, 3);
                path.lineTo(6, 13.5f);
                path.lineTo(12, 13.5f);
                path.lineTo(11, 21);
                path.lineTo(18, 10.5f);
                path.lineTo(12, 10.5f);
                path.close();
                c.drawPath(path, p);
                break;
            case UP:
                line(c, 6, 18, 18, 6);
                line(c, 9, 6, 18, 6);
                line(c, 18, 6, 18, 15);
                break;
            case DN:
                line(c, 6, 6, 18, 18);
                line(c, 9, 18, 18, 18);
                line(c, 18, 18, 18, 9);
                break;
            default:
                break;
        }
        c.restore();
    }
}
