/*
 * Copyright (C) 2002-2007 by ?
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation. This program is distributed in the hope it will
 * be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package org.esa.beam.akh.tilesubsetter;

import com.bc.ceres.core.ProgressMonitor;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.esa.beam.framework.datamodel.*;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.annotations.TargetProduct;
import org.esa.beam.util.ProductUtils;

import java.awt.*;
import java.awt.geom.Area;
import java.awt.geom.GeneralPath;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.esa.beam.framework.dataio.ProductSubsetBuilder;
import org.esa.beam.framework.dataio.ProductSubsetDef;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.input.DOMBuilder;

/**
 */
@OperatorMetadata(alias="mtsVgt",
                  description = "Operator subsetting AATSR and VGT data to MODIS sin tiles",
                  authors = "akh",
                  version = "1.0",
                  copyright = "")
public class TileSubsetOp extends Operator {

    @SourceProduct
    private Product sourceProduct;
    @TargetProduct
    private Product targetProduct;
    @Parameter
    private String tileName;
    @Parameter(defaultValue="VGT")
    private String instrument;
    private ProductSubsetBuilder subsetReader;
    private ProductSubsetDef subsetDef;
    private final int aatsrLineStep = 10;
    private final String tileCoordFile = "TileCoordinatesAll.xml";

    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public TileSubsetOp() {
    }

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link org.esa.beam.framework.datamodel.Product} annotated with the
     * {@link org.esa.beam.framework.gpf.annotations.TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws org.esa.beam.framework.gpf.OperatorException
     *          If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {

        Rectangle region = getTileRegion(tileName);

        subsetReader = new ProductSubsetBuilder();
        subsetDef = new ProductSubsetDef();
        subsetDef.addNodeNames(sourceProduct.getTiePointGridNames());
        subsetDef.addNodeNames(sourceProduct.getBandNames());
        subsetDef.addNodeNames(sourceProduct.getMetadataRoot().getElementNames());
        subsetDef.addNodeNames(sourceProduct.getMetadataRoot().getAttributeNames());
        subsetDef.setRegion(region);
        subsetDef.setSubSampling(1,1);
        subsetDef.setSubsetName(sourceProduct.getName());
        subsetDef.setIgnoreMetadata(false);

        try {
            targetProduct = subsetReader.readProductNodes(sourceProduct, subsetDef);
        } catch (Throwable t) {
            throw new OperatorException(t);
        }
    }

    @Override
    public void computeTile(Band targetBand, Tile targetTile, ProgressMonitor pm) throws OperatorException {
        ProductData destBuffer = targetTile.getRawSamples();
        Rectangle rectangle = targetTile.getRectangle();
        try {
            subsetReader.readBandRasterData(targetBand,
                                            rectangle.x,
                                            rectangle.y,
                                            rectangle.width,
                                            rectangle.height,
                                            destBuffer, pm);
            targetTile.setRawSamples(destBuffer);
        } catch (IOException e) {
            throw new OperatorException(e);
        }
    }

    /**
     * getTileRegion returns an rectangle that should contain the overlap region
     * between a given MODIS tile and the source product
     * @param tileName
     * @return overlap rectangle
     */
    private Rectangle getTileRegion(String tileName) {
        ArrayList<ModisTile> modisTileList = getModisTileList(tileCoordFile);
        ModisTile tile = null;
        for (ModisTile mt : modisTileList) {
            if (mt.name.equals(tileName)) {
                tile = mt;
                break;
            }
        }
        if (tile == null) throw new OperatorException("tile name not in XML list");

        Rectangle overlap = null;
        if (instrument.equals("VGT")){
            overlap = getTileOverlapWithVgt(sourceProduct, tile);
            trimRegion(sourceProduct, overlap);
        }
        else if (instrument.equals("AATSR")){
            overlap = getTileOverlapWithAatsr(sourceProduct, tile);
        }

        if (overlap == null || overlap.width < 200 || overlap.height < 200) throw new OperatorException("region too small");
        return overlap;
    }

    /**
     * Imports a list from MODIS tile specifications from an xml file
     * @param xmlName
     * @return
     */
    private ArrayList<ModisTile> getModisTileList(String xmlName) {
        final ArrayList<ModisTile> mtl = new ArrayList<ModisTile>(70);
        final InputStream is = TileSubsetOp.class.getResourceAsStream(xmlName);
        DocumentBuilderFactory domFac = DocumentBuilderFactory.newInstance();
        try {
            Document dom = new DOMBuilder().build(domFac.newDocumentBuilder().parse(is));
            Element root = dom.getRootElement();
            for (Object o : root.getChildren("tile")) {
                Element tile = (Element) o;
                String tileN = tile.getAttributeValue("name");
                String easting = tile.getChild("Easting").getContent(0).getValue();
                String northing = tile.getChild("Northing").getContent(0).getValue();
                Map<CornerId, GeoCorner> cornerMap = new HashMap<CornerId, GeoCorner>(4);
                for (CornerId cid : CornerId.values()){
                    String lon = tile.getChild(cid.getName()).getChild("lon").getContent(0).getValue();
                    String lat = tile.getChild(cid.getName()).getChild("lat").getContent(0).getValue();
                    cornerMap.put(cid, new GeoCorner(lon, lat));
                }
                mtl.add(new ModisTile(tileN, easting, northing, cornerMap));
            }
        } catch (Exception ex) {
            throw new OperatorException(ex);
        } finally {
            try {
                is.close();
            } catch (IOException ex) {
                throw new OperatorException(ex);
            }
        }
        return mtl;
    }

    /**
     * determines the overlap region for lat lon Plate Carreé projected VGT P products
     * @param vgtProduct
     * @param tile
     * @return
     */
    private Rectangle getTileOverlapWithVgt(Product vgtProduct, ModisTile tile) {
        int srcHeight = vgtProduct.getSceneRasterHeight();
        int srcWidth = vgtProduct.getSceneRasterWidth();

        GeneralPath sceneBoundaryPath = new GeneralPath(GeneralPath.WIND_NON_ZERO, 4);
        sceneBoundaryPath.moveTo(0, 0);
        sceneBoundaryPath.lineTo(srcWidth, 0);
        sceneBoundaryPath.lineTo(srcWidth, srcHeight);
        sceneBoundaryPath.lineTo(0, srcHeight);
        sceneBoundaryPath.closePath();

        Area a = new Area(sceneBoundaryPath);

        GeneralPath roi = getRoiFromTile(vgtProduct, tile);
        Area b = new Area(roi);
        a.intersect(b);
        if (!a.getBounds().isEmpty()) System.out.println(a.getBounds());
        return a.getBounds();
    }

    private GeneralPath getRoiFromTile(Product sourceProduct, ModisTile tile) {
        final GeoPos[] gps = new GeoPos[4];
        GeoCorner corner = tile.corners.get(CornerId.UpperLeft);
        gps[0] = new GeoPos(parseDMS(corner.lat), parseDMS(corner.lon)); //UpperLeft
        corner = tile.corners.get(CornerId.UpperRight);
        gps[1] = new GeoPos(parseDMS(corner.lat), parseDMS(corner.lon)); //UpperRight
        corner = tile.corners.get(CornerId.LowerRight);
        gps[2] = new GeoPos(parseDMS(corner.lat), parseDMS(corner.lon)); //LowerRight
        corner = tile.corners.get(CornerId.LowerLeft);
        gps[3] = new GeoPos(parseDMS(corner.lat), parseDMS(corner.lon)); //LowerLeft

        final GeoCoding srcGeoCod = sourceProduct.getGeoCoding();
        final PixelPos[] pps = new PixelPos[4];
        for (int i = 0; i < 4; i++) {
            pps[i] = srcGeoCod.getPixelPos(gps[i], null);
        }
        GeneralPath roi = new GeneralPath(GeneralPath.WIND_NON_ZERO, 4);
        roi.moveTo(pps[0].getX(), pps[0].getY());
        for (int i = 1; i < 4; i++) {
            roi.lineTo(pps[i].getX(), pps[i].getY());
        }
        roi.closePath();
        return roi;
    }

    private float parseDMS(String dms) {
        String[] stmp = dms.split("[d\'\"]");
        if (stmp.length != 4) throw new OperatorException("couldn\'t parse string " + dms);
        double d = Float.valueOf(stmp[0]);
        double m = Float.valueOf(stmp[1]);
        double s = Float.valueOf(stmp[2]);
        float sign = (stmp[3].matches("[EN]")) ? 1 : -1;
        return (float) (sign * (d + m / 60 + s / 3600));
    }

    private void trimRegion(Product p, Rectangle rec) {
        int iBand = 0;
        while (iBand < p.getNumBands() && p.getBandAt(iBand).getSpectralWavelength() == 0) {
            iBand++;
        }
        if (iBand == p.getNumBands()) {
            throw new OperatorException("Product does not contain spectral bands");
        }
        Band b = p.getBandAt(iBand);

        //System.err.println(rec);
        int i1 = rec.x;
        int i2 = rec.x + rec.width - 1;
        while (i1 < rec.x + rec.width && i2 >= rec.x) {
            boolean i1Found = false;
            boolean i2Found = false;
            for (int j = rec.y; j < rec.y + rec.height; j++) {
                i1Found = i1Found || b.isPixelValid(i1, j);
                i2Found = i2Found || b.isPixelValid(i2, j);
                if (i1Found && i2Found) {
                    break;
                }
            }
            if (i1Found && i2Found) {
                break;
            }
            if (!i1Found) {
                i1++;
            }
            if (!i2Found) {
                i2--;
            }

        }
        int j1 = rec.y;
        int j2 = rec.y + rec.height - 1;
        while (j1 < rec.y + rec.height && j2 >= rec.y) {
            boolean j1Found = false;
            boolean j2Found = false;
            for (int i = rec.x; i < rec.x + rec.width; i++) {
                j1Found = j1Found || b.isPixelValid(i, j1);
                j2Found = j2Found || b.isPixelValid(i, j2);
                if (j1Found && j2Found) {
                    break;
                }
            }
            if (j1Found && j2Found) {
                break;
            }
            if (!j1Found) {
                j1++;
            }
            if (!j2Found) {
                j2--;
            }

        }
        rec.x = i1;
        rec.width = i2 + 1 - rec.x;
        rec.y = j1;
        rec.height = j2 + 1 - rec.y;
        //System.err.println(rec);
    }

    /**
     * determines the overlap region for AATSR L1b products
     * @param aatsrProduct
     * @param tile
     * @return
     */
    private Rectangle getTileOverlapWithAatsr(Product aatsrProduct, ModisTile tile) {
        double[] latLonB = getLatLonBounds(tile);
        Rectangle geoRec = getGeoRec(aatsrProduct, latLonB[0], latLonB[1], latLonB[2], latLonB[3]);
        return geoRec;
    }

    private double[] getLatLonBounds(ModisTile tile) {
        double[] bounds = {tile.getMinLat(),
                          tile.getMaxLat(),
                          tile.getMinLon(),
                          tile.getMaxLon()};
        return bounds;
    }

    /**
     * determines regional overlap of AATSR product with a given lat/lon window
     * Attention: subsets AATSR also by Solar Elevation Angle > 30 to reduce
     * the output to the day time / descending orbit part
     * @param aatsrProduct
     * @param latmin
     * @param latmax
     * @param lonmin
     * @param lonmax
     * @return
     */
    private Rectangle getGeoRec(Product aatsrProduct, double latmin, double latmax, double lonmin, double lonmax) {
        Rectangle overlapRec = null;
        TiePointGrid latTPG = aatsrProduct.getTiePointGrid("latitude");
        TiePointGrid lonTPG = aatsrProduct.getTiePointGrid("longitude");
        TiePointGrid seaTPG = aatsrProduct.getTiePointGrid("sun_elev_nadir");
        int rasterWidth = aatsrProduct.getSceneRasterWidth();
        int rasterHeight = aatsrProduct.getSceneRasterHeight();
        float leftLat;
        float rightLat;
        float leftLon;
        float rightLon;
        boolean overlapFound = false;
        int iy=0;
        while (iy < rasterHeight) {
            if (seaTPG.getPixelFloat(0, iy) > 30) {
                leftLat = latTPG.getPixelFloat(0, iy);
                rightLat = latTPG.getPixelFloat(rasterWidth - 1, iy);
                boolean leftLatInside = (leftLat < latmax && leftLat > latmin);
                boolean rightLatInside = (rightLat < latmax && rightLat > latmin);
                if (leftLatInside || rightLatInside) {
                    leftLon = lonTPG.getPixelFloat(0, iy);
                    rightLon = lonTPG.getPixelFloat(rasterWidth - 1, iy);
                    boolean leftLonInside = (leftLon < lonmax && leftLon > lonmin);
                    boolean rightLonInside = (rightLon < lonmax && rightLon > lonmin);
                    if (leftLonInside || rightLonInside) {
                        if (!overlapFound) {
                            overlapRec = new Rectangle(0, iy, rasterWidth, 0);
                            overlapFound = true;
                        }
                    } else {
                        if (overlapFound) {
                            overlapRec.height = 1 + iy - overlapRec.y;
                            return overlapRec;
                        }
                    }
                } else {
                    if (overlapFound) {
                        overlapRec.height = 1 + iy - overlapRec.y;
                        return overlapRec;
                    }
                }
            }
            iy+=aatsrLineStep;
        }
        return overlapRec;
    }



    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.beam.framework.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     * @see OperatorSpi#createOperator()
     * @see OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(TileSubsetOp.class);
        }
    }
}
