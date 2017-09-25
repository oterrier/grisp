/**
 * Originally from https://github.com/yahoo/FEL
 * Licensed under the terms of the Apache License 2.0. See LICENSE file at the project root for terms.
 **/

package com.scienceminer.grisp.nerd.embeddings;

import it.unimi.dsi.logging.ProgressLogger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Random;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;

import com.martiansoftware.jsap.FlaggedOption;
import com.martiansoftware.jsap.JSAP;
import com.martiansoftware.jsap.JSAPException;
import com.martiansoftware.jsap.JSAPResult;
import com.martiansoftware.jsap.Parameter;
import com.martiansoftware.jsap.SimpleJSAP;

/**
 * Learns entity embeddings using regularized logistic regression and negative
 * sampling (in the paper lambda=10 and rho=20)
 *
 * Addition of standalonemainMultithread for exploiting multhreading during creation of embeddings.
 * This gives ~7 hours for producing embeddings for 4.6 millions Wikidata entities based on entity
 * descriptions corresponding to first paragraphs of English Wikipedia with 8 threads. 
 *
 * Example command:
 *
 * mvn exec:java -Dexec.mainClass=com.yahoo.semsearch.fastlinking.w2v.EntityEmbeddings 
 * -Dexec.args="-i /home/lopez/nerd/data/wikipedia/training/description.en 
 * -v /mnt/data/wikipedia/embeddings/wiki.en.q.compressed -o /mnt/data/wikipedia/embeddings/entity.en.embeddings"
 *
 * @author roi blanco (original), with modification patrice lopez
 */
public class EntityEmbeddings {   
    private Random r;

    static enum MyCounters {
        NUM_RECORDS, ERR
    };

    public EntityEmbeddings() {
        this.r = new Random( 1234 );
    }

    /**
     * Use this method to compute entity embeddings on a single machine. See --help
     * @param args command line arguments
     * @throws JSAPException
     * @throws ClassNotFoundException
     * @throws IOException
     */
    public static void standalonemain( String args[] ) throws JSAPException, ClassNotFoundException, IOException {
        SimpleJSAP jsap = new SimpleJSAP( EntityEmbeddings.class.getName(), "Learns entity embeddings", new Parameter[]{
                new FlaggedOption( "input", JSAP.STRING_PARSER, JSAP.NO_DEFAULT, JSAP.REQUIRED, 'i', "input", "Entity description files" ),
                new FlaggedOption( "vectors", JSAP.STRING_PARSER, JSAP.NO_DEFAULT, JSAP.REQUIRED, 'v', "vectors", "Word2Vec file" ),
                new FlaggedOption( "rho", JSAP.INTEGER_PARSER, "-1", JSAP.NOT_REQUIRED, 'r', "rho", "rho negative sampling parameters (if it's <0 we use even sampling)" ),
                new FlaggedOption( "max", JSAP.INTEGER_PARSER, "-1", JSAP.NOT_REQUIRED, 'm', "max", "Max words per entity (<0 we use all the words)" ),
                new FlaggedOption( "output", JSAP.STRING_PARSER, JSAP.NO_DEFAULT, JSAP.REQUIRED, 'o', "output", "Compressed version" ), }
        );
        JSAPResult jsapResult = jsap.parse( args );
        if( jsap.messagePrinted() ) return;
        CompressedW2V vectors = new CompressedW2V( jsapResult.getString( "vectors" ) );
        ProgressLogger pl = new ProgressLogger();
        final int rho = jsapResult.getInt( "rho" );
        final int nwords = vectors.getSize();
        final int d = vectors.N;
        final int maxWords = jsapResult.getInt( "max" ) > 0? jsapResult.getInt( "max" ):Integer.MAX_VALUE;
        final BufferedReader br = new BufferedReader( new InputStreamReader( new FileInputStream( new File( jsapResult.getString( "input" ) ) ) ) );
        int count = 0;
        pl.count = count;
        pl.itemsName = "entities";
        while( br.readLine() != null ) count++;
        br.close();
        final PrintWriter pw = new PrintWriter( new BufferedWriter( new FileWriter( jsapResult.getString( "output" ), false ) ) );
        pw.println( count + " " + d );

        float alpha = 10;
        EntityEmbeddings eb = new EntityEmbeddings();
        final BufferedReader br2 = new BufferedReader( new InputStreamReader( new FileInputStream( new File( jsapResult.getString( "input" ) ) ) , "UTF-8") );
        pl.start();
        String line;
        int nbWritten = 0;
        while( ( line = br2.readLine() ) != null ) {
            String[] parts = line.split( "\t" );
            if( parts.length > 1 ) {
                TrainingExamples ex = eb.getVectors( parts[ 1 ], vectors, rho, nwords, maxWords );
        		if (ex.y.length == 0) {
        		    continue;
                }
                float[] w = eb.trainLR2( ex.x, d, ex.y, alpha );
                pw.print( parts[ 0 ] + " " );
                for( int i = 0; i < d; i++ ) {
                    pw.print( w[ i ] + " " );
                }
                pw.println();
                nbWritten++;
                pl.lightUpdate();
                if (nbWritten == 1000) {
                    pw.flush();
                    nbWritten = 0;
                }

                for( int i = 0; i < ex.y.length; i++ ) {
                    if( ex.y[ i ] > 0 ) {
                        double v = eb.scoreLR( ex.x[ i ], w );
                    }
                }
            }
        }
        br2.close();
        pw.close();
        pl.stop();
    }

    /**
     * Use this method to compute entity embeddings on a single machine. See --help
     * @param args command line arguments
     * @throws JSAPException
     * @throws ClassNotFoundException
     * @throws IOException
     */
    public static void standalonemainMultithread( String args[] ) throws JSAPException, ClassNotFoundException, IOException {
        SimpleJSAP jsap = new SimpleJSAP( EntityEmbeddings.class.getName(), "Learns entity embeddings", new Parameter[]{
                new FlaggedOption( "input", JSAP.STRING_PARSER, JSAP.NO_DEFAULT, JSAP.REQUIRED, 'i', "input", "Entity description files" ),
                new FlaggedOption( "vectors", JSAP.STRING_PARSER, JSAP.NO_DEFAULT, JSAP.REQUIRED, 'v', "vectors", "Word2Vec file" ),
                new FlaggedOption( "rho", JSAP.INTEGER_PARSER, "-1", JSAP.NOT_REQUIRED, 'r', "rho", "rho negative sampling parameters (if it's <0 we use even sampling)" ),
                new FlaggedOption( "max", JSAP.INTEGER_PARSER, "-1", JSAP.NOT_REQUIRED, 'm', "max", "Max words per entity (<0 we use all the words)" ),
                new FlaggedOption( "output", JSAP.STRING_PARSER, JSAP.NO_DEFAULT, JSAP.REQUIRED, 'o', "output", "Compressed version" ), }
        );

        final int NBTHREADS = 8;

        JSAPResult jsapResult = jsap.parse( args );
        if( jsap.messagePrinted() ) return;
        CompressedW2V vectors = new CompressedW2V( jsapResult.getString( "vectors" ) );
        final int rho = jsapResult.getInt( "rho" );
        final int nwords = vectors.getSize();
        final int d = vectors.N;
        final int maxWords = jsapResult.getInt( "max" ) > 0? jsapResult.getInt( "max" ):Integer.MAX_VALUE;
        final BufferedReader br = new BufferedReader( new InputStreamReader( new FileInputStream( new File( jsapResult.getString( "input" ) ) ) ) );
        int count = 0;

        while( br.readLine() != null ) count++;
        br.close();

        EntityEmbeddings eb = new EntityEmbeddings();
        ExecutorService executor = Executors.newFixedThreadPool(NBTHREADS);
        for(int i=0; i<NBTHREADS; i++) {
            Runnable worker = new EntityCruncher(i, jsapResult.getString("input"), jsapResult.getString("output"), vectors, rho, count, maxWords, NBTHREADS);
            executor.execute(worker);
        }
        try {
            System.out.println("wait for thread completion");
            executor.shutdown();
            executor.awaitTermination(48, TimeUnit.HOURS);
        } catch(InterruptedException e) {
            System.err.println("worker interrupted");
        } finally {
            if (!executor.isTerminated()) {
                System.err.println("cancel all non-finished workers");
            }
            executor.shutdownNow();
        }
    }

    public static class EntityCruncher implements Runnable {
        private final int rank;
        private final String input;
        private final String output;
        private final CompressedW2V vectors;
        private final int rho;
        private final int count; 
        private final int maxWords;
        private final int nbThreads;

        EntityCruncher(int rank, String input, String output, CompressedW2V vectors, int rho, int count, int maxWords, int nbThreads) {
            this.rank = rank;
            this.input = input;
            this.output = output;
            this.vectors = vectors;
            this.rho = rho;
            this.count = count;
            this.maxWords = maxWords;
            this.nbThreads = nbThreads;
        }

        @Override
        public void run() {
            ProgressLogger pl = new ProgressLogger();
            final int nwords = vectors.getSize();
            final int d = vectors.N;
            try {
                pl.count = count / nbThreads;
                pl.itemsName = "entities";
                final PrintWriter pw = new PrintWriter(new BufferedWriter(new FileWriter(output+"."+rank, false)));
                if (rank == 0)
                    pw.println( count + " " + d );

                float alpha = 10;
                EntityEmbeddings eb = new EntityEmbeddings();
                final BufferedReader br = new BufferedReader( new InputStreamReader( new FileInputStream( new File(input) ) , "UTF-8") );
                pl.start();
                String line;
                int nb = 0;
                int nbWritten = 0;
                while( ( line = br.readLine() ) != null ) {
                    if ( (nb < (count/nbThreads)*rank) ) {
                        nb++; 
                        continue;
                    }

                    if ( (rank != nbThreads-1) && (nb > (count/nbThreads)*(rank+1)) )
                        break;

                    String[] parts = line.split( "\t" );
                    if( parts.length > 1 ) {
                        TrainingExamples ex = eb.getVectors( parts[ 1 ], vectors, rho, nwords, maxWords);
                        if (ex.y.length == 0) {
                            nb++;
                            continue;
                        }
                        float[] w = eb.trainLR2( ex.x, d, ex.y, alpha );
                        pw.print( parts[ 0 ] + " " );
                        for( int i = 0; i < d; i++ ) {
                            pw.print( w[ i ] + " " );
                        }
                        pw.println();
                        pl.lightUpdate();
                        nbWritten++;
                        if (nbWritten == 1000) {
                            pw.flush();
                            nbWritten = 0;
                        }

                        for( int i = 0; i < ex.y.length; i++ ) {
                            if( ex.y[ i ] > 0 ) {
                                double v = eb.scoreLR( ex.x[ i ], w );
                            }
                        }
                    }
                    nb++;
                }
                br.close();
                pw.close();
                pl.stop();
            } catch(Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Holder for ({x},y) sets
     */
    public class TrainingExamples {
        public TrainingExamples( float[][] x, int[] y ) {
            this.x = x;
            this.y = y;
        }
        public float[][] x;
        public int[] y;
    }

    /**
     * Gets a set of training examples out of a chunk of text using its word embeddings as features and negative sampling
     * @param input text to extract features from
     * @param vectors word embeddings
     * @param rho number of words to sample negatively
     * @param nwords total words in the vocabulary
     * @return training examples
     */
    public TrainingExamples getVectors( String input, CompressedW2V vectors, int rho, int nwords, int maxWordsPerEntity ) {
        String[] parts = input.split( "\\s+" );
        ArrayList<float[]> positive = new ArrayList<float[]>();
        ArrayList<float[]> negative = new ArrayList<float[]>();

        HashSet<Long> positiveWords = new HashSet<Long>();
        int tmp = 0;
        for( String s : parts ) {
            float[] vectorOf = vectors.getVectorOf( s );
            if( vectorOf != null ) {
                positive.add( vectorOf );
                positiveWords.add( vectors.getWordId( s ) );
                tmp++;
            }
            if( tmp > maxWordsPerEntity ) break;
        }

        int total = 0;
        if( rho < 0 ) rho = positive.size();
        while( total < rho ) {
            long xx = r.nextInt( nwords );
            while( positiveWords.contains( xx ) ) xx = r.nextInt( nwords );
            negative.add( vectors.get( xx ) );
            total++;
        }

        float[][] x = new float[ positive.size() + negative.size() ][ vectors.N ];
        int[] y = new int[ positive.size() + negative.size() ];

        for( int i = 0; i < positive.size(); i++ ) {
            x[ i ] = positive.get( i );
            y[ i ] = 1;
        }
        final int j = positive.size();
        for( int i = 0; i < negative.size(); i++ ) {
            x[ i + j ] = negative.get( i );
            y[ i + j ] = 0;
        }
        return new TrainingExamples( x, y );
    }

    /**
     * Initializes randomly the weights
     * @param N number of dimensions
     * @return a vector of N dimensions with random weights
     */
    public float[] initWeights( int N ) {
        float[] w = new float[ N ];
        for( int i = 0; i < N; i++ ) {
            w[ i ] = r.nextFloat();
        }
        return w;
    }

    /**
     * Sigmoid score
     * @param w weights
     * @param x input
     * @return 1/(1+e^( - w * x ) )
     */
    public double scoreLR( float[] w, float[] x ) {
        float inner = 0;
        for( int i = 0; i < w.length; i++ ) {
            inner += w[ i ] * x[ i ];
        }
        return 1d / ( 1 + Math.exp( -inner ) );
    }

    /**
     * Learns the weights of a L2 regularized LR algorithm
     * @param x input data
     * @param d number of dimensions
     * @param y labels
     * @param C loss-regularizer tradeoff parameter
     * @return learned weights
     */
    public float[] trainLR2( float[][] x, int d, int[] y, float C ) { //m examples. dim = N
        C = C / 2;
        final int maxIter = 50000;
        double alpha = 1D;
        final int N = y.length;
        final double tolerance = 0.00001;
        float[] w = initWeights( d );
        double preLik = 100;
        boolean convergence = false;
        int iter = 0;
        while( !convergence ) {
            double likelihood = 0;
            double[] currentScores = new double[ N ];
            float acumBias = 0;
            for( int i = 0; i < N; i++ ) {
                currentScores[ i ] = scoreLR( w, x[ i ] ) - y[ i ];
                acumBias += currentScores[ i ] * x[ i ][ 0 ];
            }
            w[ 0 ] = ( float ) ( w[ 0 ] - alpha * ( 1D / N ) * acumBias ); //bias doesn't regularize
            for( int j = 1; j < d; j++ ) {
                float acum = 0;
                for( int i = 0; i < N; i++ ) {
                    acum += currentScores[ i ] * x[ i ][ j ];
                }
                w[ j ] = ( float ) ( w[ j ] - alpha * ( ( 1D / N ) * ( acum + C * w[ j ] ) ) );

            }

            double norm = 0;
            for( int j = 0; j < d; j++ ) {
                norm += w[ j ] * w[ j ];
            }
            norm = ( C / N ) * norm;
            for( int i = 0; i < N; i++ ) {
                double nS = scoreLR( w, x[ i ] );
                if( nS > 0 ) {
                    double s = y[ i ] * Math.log( nS ) + ( 1 - y[ i ] ) * Math.log( 1 - nS );
                    if( !Double.isNaN( s ) ) likelihood += s;
                }
            }
            likelihood = norm - ( 1 / N ) * likelihood;
            iter++;
            if( iter > maxIter ) convergence = true;
            else if( Math.abs( likelihood - preLik ) < tolerance ) convergence = true;
            if( likelihood > preLik ) alpha /= 2;

            preLik = likelihood;

        }
        return w;
    }

    public static void main( String[] args ) throws Exception {
        //standalonemain(args);
        standalonemainMultithread(args);
        System.exit(0);
    }
}