/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.esa.beam.akh.tilesubsetter;

/**
 *
 * @author akheckel
 */
public enum CornerId {
    UpperLeft("UpperLeft"),
    UpperRight("UpperRight"),
    LowerRight("LowerRight"),
    LowerLeft("LowerLeft");
    private final String name;

    private CornerId(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

}
