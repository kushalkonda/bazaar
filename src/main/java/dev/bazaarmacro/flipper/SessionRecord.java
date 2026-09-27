package dev.bazaarmacro.flipper;

/**
 * One completed flip session's outcome - a pure purse delta, same ground truth
 * {@link FlipperProfitTracker#trueProfit()} uses, so history numbers always agree with what the HUD
 * showed live. The ending purse isn't stored: it was only ever written as
 * {@code startingPurse + profit} and never read back, so persisting it risked the two disagreeing
 * on an old record with no way to tell which was right.
 */
public record SessionRecord(String itemTag, long startingPurse, long profit,
                             long startTimeMs, long endTimeMs) {
}
