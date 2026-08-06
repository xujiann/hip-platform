package cn.hip.bureau.common;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** 工作日计算（跳过周六周日；法定节假日暂不建表，与 ADR-0003 一致） */
public final class Workdays {

    private Workdays() {}

    /** 自 from 起加 n 个工作日（期间开始之日不计算在内，第58条） */
    public static LocalDate plus(LocalDate from, int n) {
        LocalDate d = from;
        int added = 0;
        while (added < n) {
            d = d.plusDays(1);
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return d;
    }
}
