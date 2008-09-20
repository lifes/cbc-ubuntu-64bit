package net.loveruby.cflat.asm;

public class IntegerLiteral extends Literal {
    protected long value;

    public IntegerLiteral(long n) {
        this.value = n;
    }

    public boolean equals(Object other) {
        if (!(other instanceof IntegerLiteral)) return false;
        IntegerLiteral lit = (IntegerLiteral)other;
        return lit.value == this.value;
    }

    public long value() {
        return this.value;
    }

    public IntegerLiteral integerLiteral() {
        return this;
    }

    public String toSource() {
        return new Long(value).toString();
    }
}