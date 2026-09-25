package com.lonelyBlock.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机数值封装 - 支持固定值与随机范围
 *
 * 解析格式 (统一解析, 适用于所有数值字段):
 *   "5"            - 固定值 5
 *   "5.5"          - 固定值 5.5
 *   "1_5"          - 随机范围 [1, 5], 默认 0 位小数 (返回整数结果)
 *   "1.5_5.5"      - 随机范围 [1.5, 5.5], 默认 0 位小数
 *   "1.5_5.5_2"    - 随机范围 [1.5, 5.5], 保留 2 位小数
 *   "1.5_5.5_0"    - 随机范围 [1.5, 5.5], 保留 0 位小数 (整数)
 *
 * 用法:
 *   - 掉落数量等整数字段: 调用 {@link #rollInt()}
 *   - 金币/时间/概率等小数字段: 调用 {@link #roll()}
 *   - 概率字段: 调用 {@link #roll()} (返回 double)
 *
 * 说明:
 *   - 解析失败时返回固定值 0
 *   - 范围 min > max 时自动交换
 *   - 小数位数为负数时按 0 处理
 */
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

    /** 固定值 */
    public static RandomValue of(double fixed) {
        return new RandomValue(fixed, fixed, 0, false);
    }

    /** 随机范围 (指定小数位数) */
    public static RandomValue range(double min, double max, int decimals) {
        return new RandomValue(min, max, decimals, true);
    }

    /**
     * 解析字符串为 RandomValue
     * 支持格式: "5" / "5.5" / "1_5" / "1.5_5.5" / "1.5_5.5_2"
     *
     * @param str 原始字符串, 可为 null
     * @return RandomValue, 解析失败返回 of(0)
     */
    public static RandomValue parse(String str) {
        if (str == null) {
            return of(0);
        }
        String s = str.trim();
        if (s.isEmpty()) {
            return of(0);
        }
        // 用下划线分隔
        String[] parts = s.split("_");
        if (parts.length == 1) {
            // 固定值
            try {
                return of(Double.parseDouble(parts[0].trim()));
            } catch (NumberFormatException e) {
                return of(0);
            }
        }
        if (parts.length == 2) {
            // min_max -> 0 位小数
            try {
                double lo = Double.parseDouble(parts[0].trim());
                double hi = Double.parseDouble(parts[1].trim());
                return range(lo, hi, 0);
            } catch (NumberFormatException e) {
                return of(0);
            }
        }
        if (parts.length == 3) {
            // min_max_decimals
            try {
                double lo = Double.parseDouble(parts[0].trim());
                double hi = Double.parseDouble(parts[1].trim());
                int dec = Integer.parseInt(parts[2].trim());
                return range(lo, hi, dec);
            } catch (NumberFormatException e) {
                return of(0);
            }
        }
        // 超过 3 段, 取前 3 段
        try {
            double lo = Double.parseDouble(parts[0].trim());
            double hi = Double.parseDouble(parts[1].trim());
            int dec = Integer.parseInt(parts[2].trim());
            return range(lo, hi, dec);
        } catch (NumberFormatException e) {
            return of(0);
        }
    }

    /**
     * 从 Object 解析 (兼容 Number / String)
     */
    public static RandomValue parse(Object obj) {
        if (obj == null) {
            return of(0);
        }
        if (obj instanceof Number) {
            return of(((Number) obj).doubleValue());
        }
        return parse(obj.toString());
    }

    /**
     * 滚动取值
     * - 固定值: 返回该值
     * - 随机范围: 返回 [min, max] 内的随机值, 按 decimals 四舍五入
     */
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

    /**
     * 滚动取整数值
     */
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

    /**
     * 是否为 0 (固定值 0, 通常表示不设置)
     */
    public boolean isZero() {
        return !isRandom && min == 0;
    }

    /**
     * 是否大于 0 (固定值 > 0 或随机范围)
     */
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
