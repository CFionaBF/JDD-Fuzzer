package cn.edu.fudan.utils.distance.drawing.theme.table;


import cn.edu.fudan.utils.distance.drawing.canvas.Drawable;
import cn.edu.fudan.utils.distance.drawing.canvas.TextAlign;

public class FixedWidthOneLineTable extends OneLineTable implements Drawable {
    public final int fixedWidth;

    public FixedWidthOneLineTable(String[][] matrix, TextAlign align, int fixedWidth) {
        super(matrix, align);
        this.fixedWidth = fixedWidth;
    }

    @Override
    protected int getCellLength(int row, int col) {
        return fixedWidth;
    }
}
