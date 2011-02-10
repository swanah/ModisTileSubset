/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.esa.beam.akh.tilesubsetter;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.esa.beam.framework.gpf.OperatorException;

/**
 *
 * @author akheckel
 */
public class ModisTile {
    public final String name;
    public final Map<CornerId, GeoCorner> corners;
    public final String easting;
    public final String northing;

    public ModisTile(String name, String easting, String northing, Map<CornerId, GeoCorner> corners) {
        this.name = name;
        this.easting = easting;
        this.northing = northing;
        this.corners = corners;
    }

    public double getMinLat(){
        double minLat = 999;
        for (GeoCorner c : corners.values()){
            double lat = parseDMS(c.lat);
            if (lat < minLat) minLat = lat;
        }
        return minLat;
    }

    public double getMinLon(){
        double minLon = 999;
        for (GeoCorner c : corners.values()){
            double lon = parseDMS(c.lon);
            if (lon < minLon) minLon = lon;
        }
        return minLon;
    }

    public double getMaxLat(){
        double maxLat = -999;
        for (GeoCorner c : corners.values()){
            double lat = parseDMS(c.lat);
            if (lat > maxLat) maxLat = lat;
        }
        return maxLat;
    }

    public double getMaxLon(){
        double maxLon = -999;
        for (GeoCorner c : corners.values()){
            double lon = parseDMS(c.lon);
            if (lon > maxLon) maxLon = lon;
        }
        return maxLon;
    }

    public double parseDMS(String dms) {
        String[] stmp = dms.split("[d\'\"]");
        if (stmp.length != 4) throw new OperatorException("couldn\'t parse string " + dms);
        double d = Float.valueOf(stmp[0]);
        double m = Float.valueOf(stmp[1]);
        double s = Float.valueOf(stmp[2]);
        float sign = (stmp[3].matches("[EN]")) ? 1 : -1;
        return (float) (sign * (d + m / 60 + s / 3600));
    }

}

