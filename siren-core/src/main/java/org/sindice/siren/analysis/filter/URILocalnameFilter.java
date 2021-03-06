/**
 * Copyright 2009, Renaud Delbru
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
/**
 * @project siren
 * @author Renaud Delbru [ 8 Dec 2009 ]
 * @link http://renaud.delbru.fr/
 * @copyright Copyright (C) 2009 by Renaud Delbru, All rights reserved.
 */
package org.sindice.siren.analysis.filter;

import java.io.IOException;
import java.io.StringReader;
import java.nio.CharBuffer;

import org.apache.lucene.analysis.TokenFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.WhitespaceAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.apache.lucene.util.Version;
import org.sindice.siren.analysis.TupleTokenizer;

/**
 * Extract the localname of an URI, and break it into smaller components based
 * on delimiters, such as uppercase or integers.
 * <br>
 * This filter returns the complete URI, the full localname of the URIs as well
 * as the localname tokens.
 * <br>
 * This filter is less demanding than the {@link URINormalisationFilter}
 * in term of CPU. In addition, it is also less costly in term of index size
 * since it creates less tokens per URI.
 * <br>
 * Before tokenisation, check the length of the localname. If the localname is
 * too large, it is not tokenised. By default, the maximum localname length is
 * set to 64.
 */
public class URILocalnameFilter
extends TokenFilter {

  public static final int DEFAULT_MAX_LENGTH = 64;
  private int maxLength = DEFAULT_MAX_LENGTH;

  protected boolean _isNormalising = false;
  protected boolean _shouldReturnLocalname = false;
  protected int     _nTokens = 0;

  private int    startLocalname;
  private int    start;
  private int    end;
  private int    termLength;
  private CharBuffer termBuffer;

  private final CharTermAttribute termAtt;
  private final TypeAttribute typeAtt;
  private final PositionIncrementAttribute posIncrAtt;

  public URILocalnameFilter(final TokenStream input) {
    super(input);
    termAtt = this.addAttribute(CharTermAttribute.class);
    typeAtt = this.addAttribute(TypeAttribute.class);
    posIncrAtt = this.addAttribute(PositionIncrementAttribute.class);
    termBuffer = CharBuffer.allocate(256);
  }

  public void setMaxLength(final int maxLength) {
    this.maxLength = maxLength;
  }

  @Override
  public final boolean incrementToken() throws java.io.IOException {

    // While we are normalising the URI
    if (_isNormalising) {
      this.nextToken();
      return true;
    }

    // Otherwise, get next URI token and start normalisation
    if (input.incrementToken()) {
      final String type = typeAtt.type();
      if (type.equals(TupleTokenizer.getTokenTypes()[TupleTokenizer.URI])) {
        termLength = termAtt.length();
        this.updateBuffer();
        _isNormalising = true;
        _shouldReturnLocalname = false; // we return the full localname only if a breakpoint is found
        _nTokens = 0;
        startLocalname = start = end = 0;
        startLocalname = start = this.findLocalname();
        this.nextToken();
      }
      return true;
    }

    return false;
  }

  protected void updateBuffer() {
    if (termBuffer.capacity() > termLength) {
      termBuffer.clear();
      termBuffer.put(termAtt.buffer(), 0, termLength);
    }
    else {
      termBuffer = CharBuffer.allocate(termLength);
      termBuffer.put(termAtt.buffer(), 0, termLength);
    }
  }

  /**
   * Find the offset of the localname delimiter. If no localname delimiter is
   * found, return last offset, i.e., {@code termLength}.
   */
  protected int findLocalname() {
    int ptr = termLength - 1;

    while (ptr > 0) {
      if (this.isLocalnameDelim(termBuffer.get(ptr))) {
        return ptr;
      }
      ptr--;
    }

    return termLength;
  }

  protected void nextToken() {
    // There is still delimiters
    while (this.findNextToken()) {
      // SRN-66 & SRN-79: skip tokens with less than 3 characters
      if (end - start < 3) {
        start = end;
        continue;
      }
      this.updateToken();
      _nTokens++;
      return;
    }

    if (_shouldReturnLocalname && startLocalname < termLength) { // return the full localname
      this.updateLocalnameToken();
      _shouldReturnLocalname = false;
      return;
    }

    // No more delimiters, we have to return the full URI as last step
    this.updateFinalToken();
    _isNormalising = false;
  }

  protected boolean findNextToken() {
    // If localname is too large, do not tokenise it
    if (termLength - start > maxLength) {
      start++; // increment start pointer since it points to a delimiter
      end = termLength;
      return true;
    }

    while (start < termLength) {
      if (this.isDelim(termBuffer.get(start))) {
        start++; continue;
      }
      else {
        end = start;
        do {
          end++;
        } while (end < termLength && !this.isBreakPoint(termBuffer.get(end)));
        if (end < termLength) { // we found a breakpoint, we should return the fulle localname
          _shouldReturnLocalname = true;
        }
        return true;
      }
    }

    return false;
  }

  protected void updateToken() {
    termAtt.copyBuffer(termBuffer.array(), start, end - start);
    start = end;
  }

  protected void updateLocalnameToken() {
    termAtt.copyBuffer(termBuffer.array(), startLocalname + 1, termLength - (startLocalname + 1));
    posIncrAtt.setPositionIncrement(0);
  }

  protected void updateFinalToken() {
    termAtt.copyBuffer(termBuffer.array(), 0, termLength);
    // SRN-80: wrong position increment if no previous tokens
    final int posInc = _nTokens == 0 ? 1 : 0;
    posIncrAtt.setPositionIncrement(posInc);
  }

  protected boolean isLocalnameDelim(final char c) {
    return c == '#' || c == '/';
  }

  protected boolean isBreakPoint(final int c) {
    return this.isDelim(c) || this.isUppercase(c);
  }

  protected boolean isDelim(final int c) {
    return Character.isLetterOrDigit(c) ? false : true;
  }

  protected boolean isUppercase(final int c) {
    return Character.isUpperCase(c) ? true : false;
  }

  /**
   * For testing purpose
   */
  public static void main(final String[] args) throws IOException {
    final TupleTokenizer stream = new TupleTokenizer(new StringReader("" +
    		"<mailto:renaud.delbru@deri.org> <http://renaud.delbru.fr/rdf/foaf> " +
    		"<http://renaud.delbru.fr/>  <http://xmlns.com/foaf/0.1/workplaceHomepage> " +
    		"<http://test.com/M%C3%B6ller>"),
    		Integer.MAX_VALUE, new WhitespaceAnalyzer(Version.LUCENE_31));
    final TokenStream result = new URILocalnameFilter(stream);
    final CharTermAttribute termAtt = result.getAttribute(CharTermAttribute.class);
    final PositionIncrementAttribute posIncrAtt = result.getAttribute(PositionIncrementAttribute.class);
    while (result.incrementToken()) {
      System.out.println(termAtt.toString() + ", " + posIncrAtt.getPositionIncrement());
    }
  }

}
