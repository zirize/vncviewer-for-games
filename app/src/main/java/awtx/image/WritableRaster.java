package awtx.image;
public class WritableRaster extends Raster {
  public SampleModel getSampleModel() {return new SampleModel();}
  public void setDataElements(int x, int y, Object r) {}
  public Object getDataElements(int x, int y, Object o) {return null;}
  public WritableRaster createCompatibleWritableRaster() {return null;}
  public WritableRaster createCompatibleWritableRaster(int w, int h) {return null;}
  public Raster createChild(int x, int y, int w, int h, int x0, int y0, int[] b) {return null;}
  public WritableRaster createWritableChild(int x, int y, int w, int h, int x0, int y0, int[] b) {return null;}
}
