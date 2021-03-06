/*
 * Copyright 2016 Tom Hall.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.solera.defrag;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.support.annotation.LayoutRes;
import android.support.annotation.MainThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import auto.parcel.AutoParcel;
import java.io.Serializable;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Handles a stack of views, and animations between these views.
 */
@MainThread
public class ViewStack {
  //Explicitly create a new string - as we use this reference as a token
  public static final Serializable USE_EXISTING_SAVED_STATE = new String("");
  private static final String SAVE_STATE_NAME = ViewStack.class.getCanonicalName();
  private static final String SERVICE_NAME = "view_stack";
  private static final int DEFAULT_ANIMATION_DURATION_IN_MS = 300;
  private final FrameLayout mFrameLayout;
  private final Collection<ViewStackListener> mViewStackListeners = new ArrayList<>();
  private Deque<ViewStackEntry> mViewStack = new ArrayDeque<>();
  private TraversingState mTraversingState = TraversingState.IDLE;
  private Object mResult;

  public ViewStack(@NonNull FrameLayout frameLayout, @Nullable Bundle saveState) {
    mFrameLayout = frameLayout;
    final SaveState parcelable =
        saveState == null ? null : (SaveState) saveState.getParcelable(SERVICE_NAME);
    if (parcelable != null) {
      for (SaveStateEntry entry : parcelable.stack()) {
        mViewStack.add(new ViewStackEntry(entry.layout(), entry.parameters()));
      }
      if (!mViewStack.isEmpty()) {
        mFrameLayout.addView(mViewStack.peek().getView());
      }
    }
  }

  public static boolean matchesServiceName(String serviceName) {
    return SERVICE_NAME.equals(serviceName);
  }

  public static ViewStack get(@NonNull View view) {
    return ViewStack.get(view.getContext());
  }

  @SuppressLint("WrongConstant") public static ViewStack get(@NonNull Context context) {
    //noinspection ResourceType
    return (ViewStack) context.getSystemService(SERVICE_NAME);
  }

  public void onSaveInstanceState(@NonNull Bundle outState) {
    outState.putParcelable(SAVE_STATE_NAME, SaveState.newInstance(this));
  }

  /**
   * It should be called in the {@link Activity#onStart()} in order to handle the {@link Activity}
   * lifecycle gracefully.
   */
  public void onStart() {
    if (mFrameLayout.getChildCount() == 0 && !mViewStack.isEmpty()) {
      mFrameLayout.addView(mViewStack.peek().getView());
    }
  }

  /**
   * It should be called in the {@link Activity#onStop()} in order to handle the {@link Activity}
   * lifecycle gracefully.
   */
  public void onStop() {
    if (!mViewStack.isEmpty()) {
      mFrameLayout.removeView(mViewStack.peek().getView());
    }
  }

  /**
   * It should be called in the {@link Activity#onBackPressed()} in order to handle the backpress
   * events correctly.
   *
   * @return true if the back press event was handled by the viewstack, false otherwise (and so the
   * activity should handle this event).
   */
  public boolean onBackPressed() {
    final View topView = getTopView();
    if (topView != null && topView instanceof HandlesBackPresses) {
      return ((HandlesBackPresses) topView).onBackPressed();
    }
    return pop();
  }

  @Nullable public View getTopView() {
    final ViewStackEntry peek = mViewStack.peek();
    if (peek != null) {
      return peek.getView();
    }
    return null;
  }

  public boolean pop() {
    return popWithResult(1, null);
  }

  public boolean popWithResult(int count, @Nullable Object result) {
    if (mViewStack.size() <= count) {
      return false;
    }
    mResult = result;
    setTraversingState(TraversingState.POPPING);
    final View fromView = mViewStack.pop().getView();
    while (--count > 0) {
      mViewStack.pop();
    }
    final View toView = mViewStack.peek().getView();
    mFrameLayout.addView(toView);
    ViewUtils.waitForMeasure(toView, new ViewUtils.OnMeasuredCallback() {
      @Override public void onMeasured(View view, int width, int height) {
        ViewStack.this.runAnimation(mFrameLayout, fromView, toView, Direction.BACK);
      }
    });
    return true;
  }

  private void runAnimation(final ViewGroup container, final View from, final View to,
      Direction direction) {
    Animator animator = createAnimation(from, to, direction);
    animator.addListener(new AnimatorListenerAdapter() {
      @Override public void onAnimationEnd(Animator animation) {
        container.removeView(from);
        setTraversingState(TraversingState.IDLE);
      }
    });
    animator.start();
  }

  @NonNull private Animator createAnimation(@NonNull View from, @NonNull View to,
      @NonNull Direction direction) {
    Animator animation = null;
    if (to instanceof HasTraversalAnimation) {
      animation = ((HasTraversalAnimation) to).createAnimation(from);
    }

    if (animation == null) {
      return createDefaultAnimation(from, to, direction);
    } else {
      return animation;
    }
  }

  private Animator createDefaultAnimation(View from, View to, Direction direction) {
    boolean backward = direction == Direction.BACK;

    AnimatorSet set = new AnimatorSet();

    set.setInterpolator(new OvershootInterpolator());
    set.setDuration(DEFAULT_ANIMATION_DURATION_IN_MS);

    set.play(ObjectAnimator.ofFloat(from, View.ALPHA, 0.0f));
    set.play(ObjectAnimator.ofFloat(to, View.ALPHA, 0.5f, 1.0f));

    if (backward) {
      set.play(ObjectAnimator.ofFloat(from, View.SCALE_X, 1.1f));
      set.play(ObjectAnimator.ofFloat(from, View.SCALE_Y, 1.1f));
      set.play(ObjectAnimator.ofFloat(to, View.SCALE_X, 0.9f, 1.0f));
      set.play(ObjectAnimator.ofFloat(to, View.SCALE_Y, 0.9f, 1.0f));
    } else {
      set.play(ObjectAnimator.ofFloat(from, View.SCALE_X, 0.9f));
      set.play(ObjectAnimator.ofFloat(from, View.SCALE_Y, 0.9f));
      set.play(ObjectAnimator.ofFloat(to, View.SCALE_X, 1.1f, 1.0f));
      set.play(ObjectAnimator.ofFloat(to, View.SCALE_Y, 1.1f, 1.0f));
    }

    return set;
  }

  public void addTraversingListener(@NonNull ViewStackListener listener) {
    mViewStackListeners.add(listener);
  }

  public void removeTraversingListener(@NonNull ViewStackListener listener) {
    mViewStackListeners.remove(listener);
  }

  @NonNull public TraversingState getTraversingState() {
    return mTraversingState;
  }

  private void setTraversingState(@NonNull TraversingState traversing) {
    if (traversing != TraversingState.IDLE && mTraversingState != TraversingState.IDLE) {
      throw new IllegalStateException("ViewStack is currently traversing");
    }

    mTraversingState = traversing;
    for (ViewStackListener listener : mViewStackListeners) {
      listener.onTraversing(mTraversingState);
    }
  }

  @LayoutRes public int getTopLayout() {
    return mViewStack.peek().mLayout;
  }

  public void replace(@LayoutRes int layout) {
    replace(layout, null);
  }

  public void replace(@LayoutRes int layout, @Nullable Serializable parameters) {
    setTraversingState(TraversingState.REPLACING);
    final ViewStackEntry viewStackEntry = new ViewStackEntry(layout, parameters);
    final View view = viewStackEntry.getView();
    if (mViewStack.isEmpty()) {
      throw new IllegalStateException("Replace on an empty stack");
    }

    final ViewStackEntry topEntry = mViewStack.peek();
    final View fromView = topEntry.getView();
    mViewStack.push(viewStackEntry);
    mFrameLayout.addView(view);
    ViewUtils.waitForMeasure(view, new ViewUtils.OnMeasuredCallback() {
      @Override public void onMeasured(View view, int width, int height) {
        ViewStack.this.runAnimation(mFrameLayout, fromView, view, Direction.FORWARD);
        mViewStack.remove(topEntry);
      }
    });
  }

  public int getViewCount() {
    return mViewStack.size();
  }

  /**
   * Starts the viewstack with the given layout, if the viewstack is non-empty, this operation is
   * ignored.
   *
   * @param layout the layout to start with
   */
  public void startWith(@LayoutRes int layout) {
    startWith(layout, null);
  }

  /**
   * Starts the viewstack with the given layout, if the viewstack is non-empty, this operation is
   * ignored.
   *
   * @param layout the layout to start with
   * @param parameters the start parameters
   */
  public void startWith(@LayoutRes int layout, @Nullable Serializable parameters) {
    if (mViewStack.isEmpty()) {
      checkNotEmptyAndPush(layout, parameters, false);
    }
  }

  private void checkNotEmptyAndPush(@LayoutRes int layout, @Nullable Serializable parameters,
      boolean throwIfEmpty) {
    if (throwIfEmpty && mViewStack.isEmpty()) {
      throw new IllegalStateException("push called on empty stack. Use startWith");
    }
    final ViewStackEntry viewStackEntry = new ViewStackEntry(layout, parameters);
    final View view = viewStackEntry.getView();

    setTraversingState(TraversingState.PUSHING);
    if (mViewStack.isEmpty()) {
      mViewStack.push(viewStackEntry);
      mFrameLayout.addView(view);
      ViewUtils.waitForMeasure(view, new ViewUtils.OnMeasuredCallback() {
        @Override public void onMeasured(View view, int width, int height) {
          setTraversingState(TraversingState.IDLE);
        }
      });
      return;
    }
    final View fromView = mViewStack.peek().getView();
    mViewStack.push(viewStackEntry);
    mFrameLayout.addView(view);

    ViewUtils.waitForMeasure(view, new ViewUtils.OnMeasuredCallback() {
      @Override public void onMeasured(View view, int width, int height) {
        ViewStack.this.runAnimation(mFrameLayout, fromView, view, Direction.FORWARD);
      }
    });
  }

  public void push(@LayoutRes int layout) {
    push(layout, null);
  }

  public void push(@LayoutRes int layout, @Nullable Serializable parameters) {
    checkNotEmptyAndPush(layout, parameters, true);
  }

  /**
   * Replace the current stack with the given views, if the Serializable component
   * is the USE_EXISTING_SAVED_STATE tag, then we will use that saved state for that
   * view (if it exists, and is at the right location in the stack) otherwise this will be null.
   */
  public void replaceStack(@NonNull List<Pair<Integer, Serializable>> views) {
    if (mViewStack.isEmpty()) {
      throw new IllegalStateException("replaceStack called on empty stack. Use startWith");
    }
    setTraversingState(TraversingState.REPLACING);

    final ViewStackEntry fromEntry = mViewStack.peek();

    //take a copy of the view stack:
    Deque<ViewStackEntry> copy = new ArrayDeque<>(mViewStack);

    mViewStack.clear();
    mViewStack.push(fromEntry);

    Iterator<ViewStackEntry> iterator = copy.iterator();
    for (Pair<Integer, Serializable> view : views) {
      Serializable savedParameter = view.second;
      if (view.second == USE_EXISTING_SAVED_STATE) {
        savedParameter = null;
        if (iterator != null && iterator.hasNext()) {
          final ViewStackEntry next = iterator.next();
          if (next.mLayout == view.first) {
            savedParameter = next.mParameters;
          } else {
            iterator = null;
          }
        }
      }
      mViewStack.push(new ViewStackEntry(view.first, savedParameter));
    }

    final ViewStackEntry toEntry = mViewStack.peek();

    final View toView = toEntry.getView();

    if (fromEntry.mLayout == toEntry.mLayout) {
      //if current topEntry layout is equal to the next proposed topEntry layout
      //we cannot do a transition animation
      mViewStack.remove(fromEntry);
      mFrameLayout.removeAllViews();
      mFrameLayout.addView(toView);
      ViewUtils.waitForMeasure(toView, new ViewUtils.OnMeasuredCallback() {
        @Override public void onMeasured(View view, int width, int height) {
          setTraversingState(TraversingState.IDLE);
        }
      });
    } else {
      final View fromView = fromEntry.getView();
      mFrameLayout.addView(toView);

      ViewUtils.waitForMeasure(toView, new ViewUtils.OnMeasuredCallback() {
        @Override public void onMeasured(View view, int width, int height) {
          ViewStack.this.runAnimation(mFrameLayout, fromView, toView, Direction.FORWARD);
          mViewStack.remove(fromEntry);
        }
      });
    }
  }

  /**
   * @return the result (if any) of the last popped view, and clears this result.
   */
  @Nullable public <T> T getResult() {
    final T result = (T) mResult;
    mResult = null;
    return result;
  }

  /**
   * @return the start parameters of the view/presenter
   */
  @Nullable public <T extends Serializable> T getParameters(@NonNull Object view) {
    final Iterator<ViewStackEntry> viewStackEntryIterator = mViewStack.descendingIterator();
    while (viewStackEntryIterator.hasNext()) {
      final ViewStackEntry viewStackEntry = viewStackEntryIterator.next();
      if (view == viewStackEntry.mViewReference.get()) {
        return (T) viewStackEntry.mParameters;
      }
    }
    return null;
  }

  public void setParameters(@NonNull Object view, @Nullable Serializable parameters) {
    final Iterator<ViewStackEntry> viewStackEntryIterator = mViewStack.descendingIterator();
    while (viewStackEntryIterator.hasNext()) {
      final ViewStackEntry viewStackEntry = viewStackEntryIterator.next();
      if (view == viewStackEntry.mViewReference.get()) {
        viewStackEntry.setParameters(parameters);
        return;
      }
    }
  }

  /**
   * Pop off the stack, with the given result.
   *
   * @param result the result.
   * @return true if the pop operation has been successful, false otherwise.
   */
  public boolean popWithResult(@Nullable Object result) {
    return popWithResult(1, result);
  }

  /**
   * Pop back to the given layout is on top.
   *
   * @param layout the layout to be on the top.
   * @param result the result to return to the (new) top view.
   * @return true if the pop operation has been successful, false otherwise.
   */
  public boolean popBackToWithResult(@LayoutRes int layout, @Nullable Object result) {
    final Iterator<ViewStackEntry> viewStackEntryIterator = mViewStack.iterator();
    int popCount = 0;
    while (viewStackEntryIterator.hasNext()) {
      final ViewStackEntry next = viewStackEntryIterator.next();
      if (next.mLayout == layout) {
        return popWithResult(popCount, result);
      }
      popCount++;
    }
    return false;
  }

  public boolean pop(int count) {
    return popWithResult(count, null);
  }

  private enum Direction {
    BACK,
    FORWARD
  }

  @AutoParcel static abstract class SaveState implements Parcelable {
    static SaveState newInstance(@NonNull ViewStack viewstack) {
      List<SaveStateEntry> stack = new ArrayList<>(viewstack.getViewCount());
      for (ViewStackEntry entry : viewstack.mViewStack) {
        stack.add(SaveStateEntry.newInstance(entry.mLayout, entry.mParameters));
      }
      return new AutoParcel_ViewStack_SaveState(stack);
    }

    @NonNull abstract List<SaveStateEntry> stack();
  }

  @AutoParcel static abstract class SaveStateEntry implements Parcelable {
    static SaveStateEntry newInstance(int layout, @Nullable Serializable parameters) {
      return new AutoParcel_ViewStack_SaveStateEntry(layout, parameters);
    }

    @LayoutRes abstract int layout();

    @Nullable abstract Serializable parameters();
  }

  private class ViewStackEntry {
    @LayoutRes private final int mLayout;
    @Nullable private Serializable mParameters;
    private WeakReference<View> mViewReference = new WeakReference<>(null);

    ViewStackEntry(@LayoutRes int layout, @Nullable Serializable parameters) {
      mLayout = layout;
      mParameters = parameters;
    }

    void setParameters(@Nullable Serializable parameters) {
      mParameters = parameters;
    }

    private View getView() {
      View view = mViewReference.get();
      if (view == null) {
        view = LayoutInflater.from(mFrameLayout.getContext()).inflate(mLayout, mFrameLayout, false);
        mViewReference = new WeakReference<>(view);
      }
      return view;
    }
  }
}
