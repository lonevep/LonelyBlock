package com.lonelyBlock.util;

import java.util.concurrent.ThreadLocalRandom;

 




















public final class RandomValue {

    private final double min;
    private final double max;
    private final int decimals;
    private final boolean isRandom;

    private RandomValue(double min, double max, int decimals, boolean isRandom) {
        double a = min;
        double b = max;
        if (a > b) {
            double t = a;
            a = b;
            b = t;
        }
        this.min = a;
        this.max = b;
        this.decimals = Math.max(0, decimals);
        this.isRandom = isRandom;
    }

     
    public static RandomValue of(double fixed) {
        return new RandomValue(fixed, fixed, 0, false);
    }

     
    public static RandomValue range(double min, double max, int decimals) {
        return new RandomValue(min, max, decimals, true);
    }

     






    public static RandomValue parse(String str) {
        if (str == null) {
            return of(0);
        }
        String s = str.trim();
        if (s.isEmpty()) {
            return of(0);
        }
        
        String[] parts = s.split("_");
        if (parts.length == 1) {
            
            try {
                return of(Double.parseDouble(parts[0].trim()));
            } catch (NumberFormatException e) {
                return of(0);
            }
        }
        if (parts.length == 2) {
            
            try {
                double lo = Double.parseDouble(parts[0].trim());
                double hi = Double.parseDouble(parts[1].trim());
                return range(lo, hi, 0);
            } catch (NumberFormatException e) {
                return of(0);
            }
        }
        if (parts.length == 3) {
            
            try {
                double lo = Double.parseDouble(parts[0].trim());
                double hi = Double.parseDouble(parts[1].trim());
                int dec = Integer.parseInt(parts[2].trim());
                return range(lo, hi, dec);
            } catch (NumberFormatException e) {
                return of(0);
            }
        }
        
        try {
            double lo = Double.parseDouble(parts[0].trim());
            double hi = Double.parseDouble(parts[1].trim());
            int dec = Integer.parseInt(parts[2].trim());
            return range(lo, hi, dec);
        } catch (NumberFormatException e) {
            return of(0);
        }
    }

     


    public static RandomValue parse(Object obj) {
        if (obj == null) {
            return of(0);
        }
        if (obj instanceof Number) {
            return of(((Number) obj).doubleValue());
        }
        return parse(obj.toString());
    }

     




    public double roll() {
        if (!isRandom) {
            return min;
        }
        double v;
        if (min == max) {
            v = min;
        } else {
            v = min + ThreadLocalRandom.current().nextDouble(max - min);
        }
        if (decimals <= 0) {
            return Math.round(v);
        }
        double factor = Math.pow(10, decimals);
        return Math.round(v * factor) / factor;
    }

     


    public int rollInt() {
        return (int) Math.round(roll());
    }

    public boolean isRandom() {
        return isRandom;
    }

    public double getMin() {
        return min;
    }

    public double getMax() {
        return max;
    }

    public int getDecimals() {
        return decimals;
    }

     


    public boolean isZero() {
        return !isRandom && min == 0;
    }

     


    public boolean isPositive() {
        if (isRandom) {
            return max > 0;
        }
        return min > 0;
    }

    @Override
    public String toString() {
        if (!isRandom) {
            return String.valueOf(min);
        }
        return min + "_" + max + (decimals > 0 ? "_" + decimals : "");
    }
}
