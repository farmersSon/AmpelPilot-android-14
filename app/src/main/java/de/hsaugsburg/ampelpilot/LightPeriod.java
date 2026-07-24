package de.hsaugsburg.ampelpilot;

import android.util.Log;

import org.opencv.core.Point;
import org.opencv.core.Rect;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by Mathi on 06.06.2017.
 */

public class LightPeriod {


    private int pixeldistance;
    private Map<Point, Integer> list = new HashMap<Point, Integer>();
    private int amountint;

    public LightPeriod(){
        pixeldistance=300;
        amountint = 10;
    }

    public void setPixeldistance(int distance){
        this.pixeldistance=distance;
    }

    public void setAmountint(int amount){
        this.amountint=amount;
    }


    public boolean checklight(){

        boolean temp = false;

        for (Integer i : list.values()) {
            Log.w("step", "check: 2 "+temp+" i: "+i );
            if(i>=amountint){
                return true;
            }
        }
        return temp;
    }

    private boolean checkArray(Rect[] array, Point b){
        boolean temp = false;
        for(int i = 0 ; i<array.length;i++){

            double x = array[i].tl().x-b.x;
            double y = array[i].tl().y-b.y;


            boolean bx = x < pixeldistance && (- pixeldistance < x);
            boolean by = y < pixeldistance && (- pixeldistance < y);

            Log.w("step", "checkArray: x "+x+" y: "+y + " bool: " + bx + " " +by +" "+(- pixeldistance < x)+" " + (x < pixeldistance));

            if(bx && by){
                return true;
            }else{
                Log.w("step", "checkArray: 3" + temp );
                continue;
            }
        }
        Log.w("step", "checkArray: 4"+temp );
        return temp;
    }

    public void addpoint( Rect[] array){

        Map<Point,Integer> old = this.list;
        Log.w("step", "oneSecondCheck: 1" );

        Map<Point, Integer> templist = new HashMap<Point, Integer>();
        for (Point p : old.keySet()) {
            if(checkArray(array,p)){
                if(old.get(p)<=amountint){
                    templist.put(p,old.get(p)+1);   //Punkt ist in Liste, punkt wird noch nicht als Ampel erkannt, und wird eins hochgezählt
                }else{
                    Log.w("step", "Wurde erkannt und gelöscht..." );
                    }
            }else if(old.get(p)>1){
                templist.put(p,old.get(p)-1);
            }
        }
        for (Rect a : array) {
            if(!templist.containsKey(a.tl())){
                templist.put(a.tl(),1);
            }
        }
        this.list = templist;
    }
}
