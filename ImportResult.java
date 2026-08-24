package com.offlineplayer.cineoffline;

public class ImportResult {
    public final boolean ok;
    public final Movie movie;
    public final String error;

    private ImportResult(boolean ok, Movie movie, String error) {
        this.ok = ok;
        this.movie = movie;
        this.error = error;
    }

    public static ImportResult ok(Movie movie) { return new ImportResult(true, movie, null); }
    public static ImportResult fail(String error) { return new ImportResult(false, null, error); }
}
