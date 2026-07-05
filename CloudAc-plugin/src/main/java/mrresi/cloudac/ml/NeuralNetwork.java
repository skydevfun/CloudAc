package mrresi.cloudac.ml;

import java.io.*;
import java.util.Random;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class NeuralNetwork {

    private final int inputSize;
    private final int hidden1Size;
    private final int hidden2Size;
    private final int outputSize;
    private double[][] w1;
    private double[][] w2;
    private double[][] w3;
    private double[] b1;
    private double[] b2;
    private double[] b3;
    private final double learningRate;
    private static final double L2_LAMBDA = 1e-4;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public NeuralNetwork(int inputSize, int hidden1Size, int hidden2Size, int outputSize, double learningRate) {
        this.inputSize = inputSize;
        this.hidden1Size = hidden1Size;
        this.hidden2Size = hidden2Size;
        this.outputSize = outputSize;
        this.learningRate = learningRate;
        initWeights();
    }

    private void initWeights() {
        Random rng = new Random(42L);
        w1 = heInit(rng, inputSize, hidden1Size);
        w2 = heInit(rng, hidden1Size, hidden2Size);
        w3 = heInit(rng, hidden2Size, outputSize);
        b1 = new double[hidden1Size];
        b2 = new double[hidden2Size];
        b3 = new double[outputSize];
    }

    private double[][] heInit(Random rng, int in, int out) {
        double std = Math.sqrt(2.0 / in);
        double[][] m = new double[in][out];
        for (int i = 0; i < in; i++)
            for (int j = 0; j < out; j++)
                m[i][j] = rng.nextGaussian() * std;
        return m;
    }

    private double relu(double x) {
        return Math.max(0, x);
    }

    private double reluDeriv(double x) {
        return x > 0 ? 1.0 : 0.0;
    }

    private double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-Math.max(-500, Math.min(500, x))));
    }

    private double[][] forwardFull(double[] input) {
        double[] h1pre = matVec(w1, input, b1, inputSize, hidden1Size);
        double[] h1act = applyRelu(h1pre);
        double[] h2pre = matVec(w2, h1act, b2, hidden1Size, hidden2Size);
        double[] h2act = applyRelu(h2pre);
        double[] outPre = matVec(w3, h2act, b3, hidden2Size, outputSize);
        double[] outAct = new double[outputSize];
        for (int i = 0; i < outputSize; i++) outAct[i] = sigmoid(outPre[i]);
        return new double[][]{ h1pre, h1act, h2pre, h2act, outPre, outAct };
    }

    private double[] matVec(double[][] w, double[] x, double[] bias, int inSz, int outSz) {
        double[] result = new double[outSz];
        for (int j = 0; j < outSz; j++) {
            result[j] = bias[j];
            for (int i = 0; i < inSz; i++)
                result[j] += w[i][j] * x[i];
        }
        return result;
    }

    private double[] applyRelu(double[] x) {
        double[] r = new double[x.length];
        for (int i = 0; i < x.length; i++) r[i] = relu(x[i]);
        return r;
    }

    public double predict(double[] input) {
        lock.readLock().lock();
        try {
            return forwardFull(input)[5][0];
        } finally {
            lock.readLock().unlock();
        }
    }

    public void train(double[] input, double[] target) {
        lock.writeLock().lock();
        try {
            double[][] fwd = forwardFull(input);
            double[] h1pre = fwd[0], h1act = fwd[1];
            double[] h2pre = fwd[2], h2act = fwd[3];
            double[] outAct = fwd[5];
            double[] dOut = new double[outputSize];
            for (int i = 0; i < outputSize; i++)
                dOut[i] = outAct[i] - target[i];
            double[] dH2 = new double[hidden2Size];
            for (int i = 0; i < hidden2Size; i++) {
                double err = 0;
                for (int j = 0; j < outputSize; j++) err += dOut[j] * w3[i][j];
                dH2[i] = err * reluDeriv(h2pre[i]);
            }
            double[] dH1 = new double[hidden1Size];
            for (int i = 0; i < hidden1Size; i++) {
                double err = 0;
                for (int j = 0; j < hidden2Size; j++) err += dH2[j] * w2[i][j];
                dH1[i] = err * reluDeriv(h1pre[i]);
            }
            for (int i = 0; i < hidden2Size; i++)
                for (int j = 0; j < outputSize; j++)
                    w3[i][j] -= learningRate * (dOut[j] * h2act[i] + L2_LAMBDA * w3[i][j]);
            for (int i = 0; i < hidden1Size; i++)
                for (int j = 0; j < hidden2Size; j++)
                    w2[i][j] -= learningRate * (dH2[j] * h1act[i] + L2_LAMBDA * w2[i][j]);
            for (int i = 0; i < inputSize; i++)
                for (int j = 0; j < hidden1Size; j++)
                    w1[i][j] -= learningRate * (dH1[j] * input[i] + L2_LAMBDA * w1[i][j]);
            for (int j = 0; j < outputSize; j++) b3[j] -= learningRate * dOut[j];
            for (int j = 0; j < hidden2Size; j++) b2[j] -= learningRate * dH2[j];
            for (int j = 0; j < hidden1Size; j++) b1[j] -= learningRate * dH1[j];
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void saveWeights(File file) throws IOException {
        lock.readLock().lock();
        try {
            file.getParentFile().mkdirs();
            try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
                dos.writeInt(inputSize);
                dos.writeInt(hidden1Size);
                dos.writeInt(hidden2Size);
                dos.writeInt(outputSize);
                writeMatrix(dos, w1, inputSize, hidden1Size);
                writeMatrix(dos, w2, hidden1Size, hidden2Size);
                writeMatrix(dos, w3, hidden2Size, outputSize);
                writeArray(dos, b1);
                writeArray(dos, b2);
                writeArray(dos, b3);
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean loadWeights(File file) throws IOException {
        if (!file.exists()) return false;
        lock.writeLock().lock();
        try {
            try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                int in = dis.readInt();
                int h1 = dis.readInt();
                int h2 = dis.readInt();
                int out = dis.readInt();
                if (in != inputSize || h1 != hidden1Size || h2 != hidden2Size || out != outputSize) return false;
                w1 = readMatrix(dis, inputSize, hidden1Size);
                w2 = readMatrix(dis, hidden1Size, hidden2Size);
                w3 = readMatrix(dis, hidden2Size, outputSize);
                b1 = readArray(dis, hidden1Size);
                b2 = readArray(dis, hidden2Size);
                b3 = readArray(dis, outputSize);
            }
        } finally {
            lock.writeLock().unlock();
        }
        return true;
    }

    private void writeMatrix(DataOutputStream dos, double[][] m, int rows, int cols) throws IOException {
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++)
                dos.writeDouble(m[i][j]);
    }

    private double[][] readMatrix(DataInputStream dis, int rows, int cols) throws IOException {
        double[][] m = new double[rows][cols];
        for (int i = 0; i < rows; i++)
            for (int j = 0; j < cols; j++)
                m[i][j] = dis.readDouble();
        return m;
    }

    private void writeArray(DataOutputStream dos, double[] arr) throws IOException {
        for (double v : arr) dos.writeDouble(v);
    }

    private double[] readArray(DataInputStream dis, int size) throws IOException {
        double[] arr = new double[size];
        for (int i = 0; i < size; i++) arr[i] = dis.readDouble();
        return arr;
    }
}
